.PHONY: all aar build assemble release test clean

export PATH := $(HOME)/go/bin:$(PATH)
export ANDROID_HOME := $(if $(ANDROID_HOME),$(ANDROID_HOME),$(if $(ANDROID_SDK_ROOT),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk))
export ANDROID_NDK_HOME := $(if $(ANDROID_NDK_HOME),$(ANDROID_NDK_HOME),$(shell find $(ANDROID_HOME)/ndk -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1))

ANDROID_API ?= 24
ANDROID_PKG ?= io.ladderairport.agent
GOMOBILE ?= $(shell which gomobile 2>/dev/null || echo $(HOME)/go/bin/gomobile)

all: aar assemble

aar:
	@echo "==> Building ladderagent.aar from core/mobile..."
	mkdir -p app/libs
	cd core && GOWORK=off $(GOMOBILE) bind -target=android -androidapi $(ANDROID_API) \
		-javapkg=$(ANDROID_PKG) -tags "with_quic,with_utls" \
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
