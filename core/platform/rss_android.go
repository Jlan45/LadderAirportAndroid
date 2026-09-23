//go:build android

package control

import "runtime"

// AndroidRSSProvider allows the host to inject resident set size.
var AndroidRSSProvider func() int64

// processRSSBytes returns the host-provided RSS or falls back to MemStats.Sys.
func processRSSBytes() int64 {
	if AndroidRSSProvider != nil {
		if rss := AndroidRSSProvider(); rss > 0 {
			return rss
		}
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	return int64(ms.Sys)
}
