# Canonical commands for the no-Gradle APK pipeline.
# Requires: python3, javac, aapt2, d8, apksigner on PATH; ANDROID_JAR set (see CONTRIBUTING.md).
# Usage: make deps && make apk

.PHONY: deps packages apk build install clean help

FETCH_ROOTS = \
	--root androidx.appcompat:appcompat:1.7.0 \
	--root androidx.recyclerview:recyclerview:1.3.2 \
	--root androidx.constraintlayout:constraintlayout:2.2.1 \
	--root com.google.android.material:material:1.12.0 \
	--root org.jetbrains.kotlin:kotlin-stdlib:1.9.24 \
	--root com.squareup.okhttp3:okhttp:4.12.0 \
	--root com.github.mwiede:jsch:0.2.26 \
	--root androidx.exifinterface:exifinterface:1.3.7 \
	--exclude org.jetbrains.kotlin:kotlin-stdlib-jdk7 \
	--exclude org.jetbrains.kotlin:kotlin-stdlib-jdk8 \
	--exclude org.jetbrains.kotlin:kotlin-stdlib-common

help:
	@echo "Targets:"
	@echo "  make deps      # fetch AARs/JARs into app/libs (+ packages.json)"
	@echo "  make apk       # deps + bash app/build.sh --only-apk"
	@echo "  make build     # alias for apk"
	@echo "  make install   # build and adb install -r (needs device)"
	@echo "  make clean     # remove app/build app/out app/libs"
	@echo ""
	@echo "Env: ANDROID_JAR must point at platforms/android-36/android.jar"
	@echo "     aapt2 / d8 / apksigner must be on PATH (build-tools)"

deps:
	cd app && python3 tools/fetch-aars.py $(FETCH_ROOTS)
	cd app && python3 tools/axml-package.py --all --libs libs

packages:
	cd app && python3 tools/axml-package.py --all --libs libs

apk: deps
	cd app && bash build.sh --only-apk
	@ls -l app/out/pi-android.apk

build: apk

install: apk
	adb install -r app/out/pi-android.apk

clean:
	rm -rf app/build app/out app/libs
