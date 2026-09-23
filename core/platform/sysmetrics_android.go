//go:build android

package control

import (
	"context"

	agentv1 "github.com/ladderairport/proto/gen/go/agent/v1"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// nodeSysCapabilities reports capabilities implemented under Android.
func nodeSysCapabilities() []string {
	return []string{"android-v1"}
}

func (s *Server) GetNodeMetrics(ctx context.Context, _ *agentv1.GetNodeMetricsRequest) (*agentv1.GetNodeMetricsResponse, error) {
	if s.nodeMetricsProvider != nil {
		metrics, err := s.nodeMetricsProvider(ctx)
		if err == nil && metrics != nil {
			return metrics, nil
		}
	}
	return nil, status.Error(codes.Unimplemented, "Android 节点系统指标未提供")
}

func (s *Server) GetBBRStatus(context.Context, *agentv1.GetBBRStatusRequest) (*agentv1.GetBBRStatusResponse, error) {
	return nil, status.Error(codes.Unimplemented, "Android 平台不支持 BBR 状态查询")
}

func (s *Server) SetBBR(context.Context, *agentv1.SetBBRRequest) (*agentv1.SetBBRResponse, error) {
	return nil, status.Error(codes.Unimplemented, "Android 平台不支持 BBR 控制")
}
