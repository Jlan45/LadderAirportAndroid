package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/ladderairport/agent/internal/control"
	"github.com/ladderairport/agent/internal/panelhttp"
	"github.com/ladderairport/agent/internal/version"
)

// EnrollConfig holds inputs for uplink token enrollment.
// TLS path fields are accepted so older callers keep decoding, and are ignored.
type EnrollConfig struct {
	PanelURL    string `json:"panel_url"`
	NodeID      string `json:"node_id"`
	EnrollToken string `json:"enroll_token"`
	DataDir     string `json:"data_dir"`
	TLSCert     string `json:"tls_cert,omitempty"`
	TLSKey      string `json:"tls_key,omitempty"`
	TLSCA       string `json:"tls_ca,omitempty"`
}

// EnrollResult holds the result of a successful enrollment.
// Certificate paths stay empty: Android uplink does not initialize management TLS.
type EnrollResult struct {
	OK       bool   `json:"ok"`
	Token    string `json:"token"`
	CertPath string `json:"cert_path,omitempty"`
	KeyPath  string `json:"key_path,omitempty"`
	CAPath   string `json:"ca_path,omitempty"`
	Error    string `json:"error,omitempty"`
}

type enrollResponse struct {
	ControlToken string `json:"control_token"`
}

// Enroll exchanges a one-time enrollment token or the node control token for
// the long-lived control token via POST /api/v1/agent/enroll. It does not
// generate a key or request a management certificate.
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

	body, err := json.Marshal(map[string]string{
		"node_id": cfg.NodeID,
	})
	if err != nil {
		return result, err
	}

	endpoint := strings.TrimRight(cfg.PanelURL, "/") + "/api/v1/agent/enroll"
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return result, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+cfg.EnrollToken)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := panelhttp.NewClient().Do(httpReq)
	if err != nil {
		return result, fmt.Errorf("连接 Panel 注册失败：%w", err)
	}
	defer resp.Body.Close()

	respRaw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return result, err
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return result, fmt.Errorf("Panel 注册失败 (HTTP %d)：%s", resp.StatusCode, strings.TrimSpace(string(respRaw)))
	}

	var issued enrollResponse
	if err := json.Unmarshal(respRaw, &issued); err != nil {
		return result, fmt.Errorf("解析注册响应失败：%w", err)
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
