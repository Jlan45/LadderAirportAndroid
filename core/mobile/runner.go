package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/ladderairport/agent/internal/control"
	"github.com/ladderairport/agent/internal/managementpki"
	"github.com/ladderairport/agent/internal/panelhttp"
	"github.com/ladderairport/agent/internal/protocolcert"
	"github.com/ladderairport/agent/internal/uplink"
	"github.com/ladderairport/agent/internal/uplinkws"
	"github.com/ladderairport/agent/internal/version"
	_ "github.com/sagernet/gomobile/bind"
)

// Host is the gobind-compatible callback interface implemented by Android Kotlin.
type Host interface {
	WriteLog(line string)
	NodeMetricsJSON() string
	InterfacesJSON() string
}

// Config is the configuration for Runner passed as JSON from Kotlin.
type Config struct {
	PanelURL   string `json:"panel_url"`
	NodeID     string `json:"node_id"`
	Token      string `json:"token"`
	DataDir    string `json:"data_dir"`
	TLSCert    string `json:"tls_cert"`
	TLSKey     string `json:"tls_key"`
	TLSCA      string `json:"tls_ca"`
	UplinkWS   bool   `json:"uplink_ws"`
	ReportSecs int    `json:"report_secs"`
	ConfigSecs int    `json:"config_secs"`
}

// Runner drives the agent lifecycle on Android (uplink-ws + in-process sing-box).
type Runner struct {
	mu        sync.Mutex
	cfg       Config
	host      Host
	running   bool
	cancel    context.CancelFunc
	rt        *control.BoxRuntime
	srv       *control.Server
	startedAt int64
	lastError string
	logWriter *hostLogWriter
}

type hostLogWriter struct {
	mu   sync.Mutex
	host Host
}

func (w *hostLogWriter) Write(p []byte) (n int, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.host != nil {
		lines := strings.Split(string(p), "\n")
		for _, line := range lines {
			line = strings.TrimRight(line, "\r")
			if line != "" {
				w.host.WriteLog(line)
			}
		}
	}
	return len(p), nil
}

// NewRunner creates a new Runner instance.
func NewRunner(cfgJSON string, host Host) (*Runner, error) {
	var cfg Config
	cfg.UplinkWS = true
	cfg.ReportSecs = 15
	cfg.ConfigSecs = 60

	if strings.TrimSpace(cfgJSON) != "" {
		if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
			return nil, fmt.Errorf("解析配置 JSON 失败：%w", err)
		}
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return nil, fmt.Errorf("缺少必须的 data_dir 参数")
	}
	if cfg.TLSCert == "" {
		cfg.TLSCert = filepath.Join(cfg.DataDir, "agent.crt")
	}
	if cfg.TLSKey == "" {
		cfg.TLSKey = filepath.Join(cfg.DataDir, "agent.key")
	}
	if cfg.TLSCA == "" {
		cfg.TLSCA = filepath.Join(cfg.DataDir, "ca.crt")
	}
	if cfg.ReportSecs <= 0 {
		cfg.ReportSecs = 15
	}
	if cfg.ConfigSecs <= 0 {
		cfg.ConfigSecs = 60
	}

	return &Runner{
		cfg:       cfg,
		host:      host,
		logWriter: &hostLogWriter{host: host},
	}, nil
}

// New is an alias for NewRunner for compatibility with android-agent.md.
func New(cfgJSON string, host Host) (*Runner, error) {
	return NewRunner(cfgJSON, host)
}

// Start boots control.Server, management PKI, uplink HTTP and uplink WebSocket.
func (r *Runner) Start() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running {
		return nil
	}

	if strings.TrimSpace(r.cfg.PanelURL) == "" || strings.TrimSpace(r.cfg.NodeID) == "" || strings.TrimSpace(r.cfg.Token) == "" {
		return fmt.Errorf("必须提供 panel_url、node_id 与 token")
	}
	if err := managementpki.ParsePanelURL(r.cfg.PanelURL); err != nil {
		return err
	}
	if err := os.MkdirAll(r.cfg.DataDir, 0o755); err != nil {
		return fmt.Errorf("创建数据目录失败：%w", err)
	}

	// Auto-enroll if certificates are missing and token is available.
	if !fileExists(r.cfg.TLSCert) || !fileExists(r.cfg.TLSKey) || !fileExists(r.cfg.TLSCA) {
		enrollCfg := EnrollConfig{
			PanelURL:    r.cfg.PanelURL,
			NodeID:      r.cfg.NodeID,
			EnrollToken: r.cfg.Token,
			DataDir:     r.cfg.DataDir,
			TLSCert:     r.cfg.TLSCert,
			TLSKey:      r.cfg.TLSKey,
			TLSCA:       r.cfg.TLSCA,
		}
		res, err := doEnroll(enrollCfg)
		if err != nil {
			r.lastError = fmt.Sprintf("证书不存在且自动注册失败：%v", err)
			return fmt.Errorf("%s", r.lastError)
		}
		if res.Token != "" {
			r.cfg.Token = res.Token
		}
	}

	rt := control.NewBoxRuntime(r.cfg.DataDir)
	logs := control.NewLogBuf(0)
	log.SetOutput(io.MultiWriter(os.Stderr, logs.Writer("info"), r.logWriter))

	singboxVer := control.SingboxVersion()
	agentVer := version.Version
	srv := control.NewServer(rt, agentVer, singboxVer, logs)
	srv.SetDataDir(r.cfg.DataDir)
	resolver := control.NewPublicAddressResolver()
	srv.SetPublicAddressResolver(resolver)

	protocolCerts, err := protocolcert.New(filepath.Join(r.cfg.DataDir, "protocol-certs"))
	if err == nil {
		srv.SetProtocolCertificateManager(protocolCerts)
	}

	panelHTTP := panelhttp.NewClient()
	certManager, err := managementpki.New(managementpki.Config{
		PanelURL:   r.cfg.PanelURL,
		NodeID:     r.cfg.NodeID,
		Token:      r.cfg.Token,
		CertPath:   r.cfg.TLSCert,
		KeyPath:    r.cfg.TLSKey,
		CAPath:     r.cfg.TLSCA,
		Address:    "127.0.0.1",
		GRPCPort:   0,
		HTTPClient: panelHTTP,
	})
	if err != nil {
		r.lastError = fmt.Sprintf("加载管理面 TLS 失败：%v", err)
		return fmt.Errorf("%s", r.lastError)
	}

	ctx, cancel := context.WithCancel(context.Background())
	r.cancel = cancel
	r.rt = rt
	r.srv = srv
	r.startedAt = time.Now().Unix()
	r.running = true
	r.lastError = ""

	go certManager.Run(ctx)

	uplinkClient, err := uplink.New(uplink.Config{
		PanelURL:    r.cfg.PanelURL,
		NodeID:      r.cfg.NodeID,
		Token:       r.cfg.Token,
		ReportEvery: time.Duration(r.cfg.ReportSecs) * time.Second,
		ConfigEvery: time.Duration(r.cfg.ConfigSecs) * time.Second,
		HTTPClient:  panelHTTP,
		Control:     srv,
	})
	if err == nil {
		go uplinkClient.Run(ctx)
	} else {
		log.Printf("初始化 HTTP uplink 失败：%v", err)
	}

	if r.cfg.UplinkWS {
		wsClient, err := uplinkws.New(uplinkws.Config{
			PanelURL:    r.cfg.PanelURL,
			NodeID:      r.cfg.NodeID,
			Token:       r.cfg.Token,
			ReportEvery: time.Duration(r.cfg.ReportSecs) * time.Second,
			HTTPClient:  panelHTTP,
			Server:      srv,
		})
		if err == nil {
			go wsClient.Run(ctx)
			log.Printf("Android Agent: uplink-ws 客户端已启动")
		} else {
			log.Printf("初始化 WS uplink 失败：%v", err)
		}
	}

	log.Printf("Android Agent 已启动：NodeID=%s PanelURL=%s", r.cfg.NodeID, r.cfg.PanelURL)
	return nil
}

// Stop shuts down the agent background routines and sing-box runtime.
func (r *Runner) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.running {
		return nil
	}
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	if r.rt != nil {
		_ = r.rt.Stop(context.Background())
		r.rt = nil
	}
	r.running = false
	r.srv = nil
	log.Printf("Android Agent 已停止")
	return nil
}

// IsRunning reports whether the agent is currently active.
func (r *Runner) IsRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// StatusInfo encapsulates real-time agent status.
type StatusInfo struct {
	Running        bool   `json:"running"`
	State          string `json:"state"`
	AgentVersion   string `json:"agent_version"`
	SingboxVersion string `json:"singbox_version"`
	PanelURL       string `json:"panel_url"`
	NodeID         string `json:"node_id"`
	StartedAtUnix  int64  `json:"started_at_unix"`
	UptimeSecs     int64  `json:"uptime_secs"`
	UplinkBytes    int64  `json:"uplink_bytes"`
	DownlinkBytes  int64  `json:"downlink_bytes"`
	Connections    int64  `json:"connections"`
	ConfigHash     string `json:"config_hash"`
	LastError      string `json:"last_error"`
}

// StatusJSON returns a JSON serialization of the agent status.
func (r *Runner) StatusJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()

	info := StatusInfo{
		Running:        r.running,
		State:          "stopped",
		AgentVersion:   version.Version,
		SingboxVersion: control.SingboxVersion(),
		PanelURL:       r.cfg.PanelURL,
		NodeID:         r.cfg.NodeID,
		StartedAtUnix:  r.startedAt,
		LastError:      r.lastError,
	}

	if r.running && r.rt != nil {
		ctx := context.Background()
		st := r.rt.Status(ctx)
		m := r.rt.Metrics(ctx)
		info.State = string(st.State)
		info.ConfigHash = st.ConfigHash
		if st.LastError != "" {
			info.LastError = st.LastError
		}
		info.UplinkBytes = m.UplinkBytes
		info.DownlinkBytes = m.DownlinkBytes
		info.Connections = m.Connections
		if r.startedAt > 0 {
			info.UptimeSecs = time.Now().Unix() - r.startedAt
		}
	}

	data, _ := json.Marshal(info)
	return string(data)
}

func fileExists(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}
