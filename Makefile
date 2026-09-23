.PHONY: all aar build assemble release test clean

LADDER_AIRPORT_DIR ?= /home/jlan/LadderAirport

all: aar assemble

aar:
	@echo "==> Building ladderagent.aar from $(LADDER_AIRPORT_DIR)..."
	$(MAKE) -C $(LADDER_AIRPORT_DIR) agent-android
	mkdir -p app/libs
	cp $(LADDER_AIRPORT_DIR)/bin/ladderagent.aar app/libs/ladderagent.aar
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
