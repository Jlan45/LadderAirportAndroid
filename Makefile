.PHONY: all aar build assemble release test clean

export PATH := $(HOME)/go/bin:$(PATH)
export ANDROID_HOME := $(if $(ANDROID_HOME),$(ANDROID_HOME),$(if $(ANDROID_SDK_ROOT),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk))
export ANDROID_NDK_HOME := $(if $(ANDROID_NDK_HOME),$(ANDROID_NDK_HOME),$(shell find $(ANDROID_HOME)/ndk -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1))

ANDROID_API ?= 24
ANDROID_PKG ?= io.ladderairport.agent
# 与服务器 Agent 同一套协议标签。不带 with_gvisor / with_clash_api / with_wireguard：
# 这是 FRP 节点，不是本机 VPN，那些标签会把 libgojni 撑大。
ANDROID_TAGS ?= with_quic,with_utls
GOMOBILE ?= $(shell which gomobile 2>/dev/null || echo $(HOME)/go/bin/gomobile)

all: aar assemble

aar:
	@echo "==> Building ladderagent.aar from core/mobile..."
	mkdir -p app/libs
	cd core && GOWORK=off $(GOMOBILE) bind -target=android/arm64 -androidapi $(ANDROID_API) \
		-javapkg=$(ANDROID_PKG) -tags "$(ANDROID_TAGS)" \
		-ldflags="-checklinkname=0 -s -w" \
		-o ../app/libs/ladderagent.aar ./mobile
	@echo "==> ladderagent.aar updated."

assemble:
	./gradlew assembleDebug

build: assemble

release:
	./gradlew assembleRelease

test:
	./gradlew test

clean:
	./gradlew clean
