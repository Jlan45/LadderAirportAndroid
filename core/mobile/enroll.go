package mobile

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/ladderairport/agent/internal/control"
	"github.com/ladderairport/agent/internal/fileutil"
	"github.com/ladderairport/agent/internal/panelhttp"
	"github.com/ladderairport/agent/internal/version"
)

// EnrollConfig holds inputs for the PKI enrollment flow.
type EnrollConfig struct {
	PanelURL    string `json:"panel_url"`
	NodeID      string `json:"node_id"`
	EnrollToken string `json:"enroll_token"`
	DataDir     string `json:"data_dir"`
	TLSCert     string `json:"tls_cert"`
	TLSKey      string `json:"tls_key"`
	TLSCA       string `json:"tls_ca"`
}

// EnrollResult holds the result of a successful enrollment.
type EnrollResult struct {
	OK       bool   `json:"ok"`
	Token    string `json:"token"`
	CertPath string `json:"cert_path"`
	KeyPath  string `json:"key_path"`
	CAPath   string `json:"ca_path"`
	Error    string `json:"error,omitempty"`
}

type issueCertResponse struct {
	Serial       string `json:"serial"`
	CertPEM      string `json:"cert_pem"`
	CABundlePEM  string `json:"ca_bundle_pem"`
	ControlToken string `json:"control_token"`
}

// Enroll performs PKI enrollment: generates EC key if needed, creates CSR,
// calls Panel's POST /api/v1/pki/agent-certificates, writes cert/ca files,
// and returns the control token and paths as JSON.
func Enroll(enrollJSON string) (string, error) {
	var cfg EnrollConfig
	if err := json.Unmarshal([]byte(enrollJSON), &cfg); err != nil {
		return "", fmt.Errorf("解析注册参数失败：%w", err)
	}
	res, err := doEnroll(cfg)
	if err != nil {
		res.OK = false
		res.Error = err.Error()
		data, _ := json.Marshal(res)
		return string(data), err
	}
	data, _ := json.Marshal(res)
	return string(data), nil
}

func doEnroll(cfg EnrollConfig) (EnrollResult, error) {
	result := EnrollResult{}
	if strings.TrimSpace(cfg.PanelURL) == "" || strings.TrimSpace(cfg.NodeID) == "" || strings.TrimSpace(cfg.EnrollToken) == "" {
		return result, fmt.Errorf("必须提供 panel_url、node_id 与 enroll_token")
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return result, fmt.Errorf("必须提供 data_dir")
	}
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		return result, fmt.Errorf("创建数据目录失败：%w", err)
	}

	keyPath := cfg.TLSKey
	if keyPath == "" {
		keyPath = filepath.Join(cfg.DataDir, "agent.key")
	}
	certPath := cfg.TLSCert
	if certPath == "" {
		certPath = filepath.Join(cfg.DataDir, "agent.crt")
	}
	caPath := cfg.TLSCA
	if caPath == "" {
		caPath = filepath.Join(cfg.DataDir, "ca.crt")
	}
	result.KeyPath = keyPath
	result.CertPath = certPath
	result.CAPath = caPath

	// 1. Generate or load ECDSA P-256 private key.
	var privKey *ecdsa.PrivateKey
	if fileExists(keyPath) {
		keyBytes, err := os.ReadFile(keyPath)
		if err == nil {
			block, _ := pem.Decode(keyBytes)
			if block != nil {
				if k, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
					privKey = k
				} else if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
					if ec, ok := k.(*ecdsa.PrivateKey); ok {
						privKey = ec
					}
				}
			}
		}
	}

	if privKey == nil {
		k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return result, fmt.Errorf("生成 ECDSA 私钥失败：%w", err)
		}
		privKey = k
		keyDER, err := x509.MarshalECPrivateKey(privKey)
		if err != nil {
			return result, fmt.Errorf("序列化私钥失败：%w", err)
		}
		keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
		if err := fileutil.AtomicWrite(keyPath, keyPEM, 0o600); err != nil {
			return result, fmt.Errorf("保存私钥失败：%w", err)
		}
	}

	// 2. Generate CSR with CommonName = NodeID.
	req := &x509.CertificateRequest{
		Subject: pkix.Name{CommonName: cfg.NodeID},
	}
	csrDER, err := x509.CreateCertificateRequest(rand.Reader, req, privKey)
	if err != nil {
		return result, fmt.Errorf("生成证书请求失败：%w", err)
	}
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})

	// 3. Send POST /api/v1/pki/agent-certificates to Panel.
	body, err := json.Marshal(map[string]any{
		"node_id":   cfg.NodeID,
		"csr_pem":   string(csrPEM),
		"address":   "127.0.0.1",
		"grpc_port": 0,
	})
	if err != nil {
		return result, err
	}

	endpoint := strings.TrimRight(cfg.PanelURL, "/") + "/api/v1/pki/agent-certificates"
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return result, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+cfg.EnrollToken)
	httpReq.Header.Set("Content-Type", "application/json")

	client := panelhttp.NewClient()
	resp, err := client.Do(httpReq)
	if err != nil {
		return result, fmt.Errorf("连接 Panel 申请证书失败：%w", err)
	}
	defer resp.Body.Close()

	respRaw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return result, err
	}
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return result, fmt.Errorf("Panel 证书签发失败 (HTTP %d)：%s", resp.StatusCode, strings.TrimSpace(string(respRaw)))
	}

	var issued issueCertResponse
	if err := json.Unmarshal(respRaw, &issued); err != nil {
		return result, fmt.Errorf("解析证书响应失败：%w", err)
	}
	if issued.CertPEM == "" || issued.CABundlePEM == "" {
		return result, fmt.Errorf("Panel 返回的证书材料不完整")
	}

	// 4. Write cert & CA.
	if err := fileutil.AtomicWrite(certPath, []byte(issued.CertPEM), 0o640); err != nil {
		return result, fmt.Errorf("保存证书失败：%w", err)
	}
	if err := fileutil.AtomicWrite(caPath, []byte(issued.CABundlePEM), 0o644); err != nil {
		return result, fmt.Errorf("保存 CA 证书包失败：%w", err)
	}

	token := issued.ControlToken
	if token == "" {
		token = cfg.EnrollToken
	}
	result.OK = true
	result.Token = token
	return result, nil
}

// GetVersion returns the compiled agent product version.
func GetVersion() string {
	return version.Version
}

// GetSingboxVersion returns the upstream sing-box version.
func GetSingboxVersion() string {
	return control.SingboxVersion()
}
