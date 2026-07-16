# World Bank Open Data Calc Add-In — build & install

## 1. Prerequisites

### Windows

1. **LibreOffice + SDK.** Default install path `C:\Program Files\LibreOffice`,
   with the SDK under `…\LibreOffice\sdk`. The SDK provides
   `sdk\bin\unoidl-write.exe` and `sdk\bin\javamaker.exe`.
2. **A JDK to build with** — any JDK 8 or newer (`javac`, `jar`). The build
   targets **Java 8 bytecode** (`--release 8`), so the add-in runs on the
   JRE 8 that LibreOffice accepts out of the box. Set `JAVA_HOME`, or put
   `javac` on `PATH`.
3. **Runtime JRE — nothing to change.** The component is Java-8 bytecode and
   uses only `java.net.HttpURLConnection`, so LibreOffice's existing/default
   JRE (8+) runs it as-is.

> Why not `java.net.http.HttpClient`? That needs Java 11+; targeting Java 8 +
> `HttpURLConnection` (both JDK standard library) avoids installing a new JRE
> while still meeting the "zero third-party dependencies" requirement.

### Linux / macOS

1. **A JDK 8** (`javac`, `jar`). If your distro's package manager has one
   (`apt install openjdk-8-jdk-headless`, `dnf install java-1.8.0-openjdk-devel`,
   …) use that. Otherwise, no root needed — fetch a JDK 8 build straight from
   Eclipse Adoptium/Temurin and unpack it under your home directory:

   ```bash
   curl -s "https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" \
     | grep -o '"link": *"[^"]*tar.gz"' | head -1 | cut -d'"' -f4
   # download that URL, then:
   mkdir -p ~/jdks && tar xzf OpenJDK8U-jdk_x64_linux_hotspot_*.tar.gz -C ~/jdks
   export JAVA_HOME=~/jdks/jdk8u<version>   # match the extracted directory name
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

2. **LibreOffice + SDK.** If your distro packages a matching SDK
   (`apt install libreoffice-dev libreoffice-dev-common` on Debian/Ubuntu),
   use that and skip to confirming the tools below. Otherwise, download the
   generic Linux tarballs (RPM-based; works on any distro, including
   Slackware, since we only extract the `.rpm`s, not install them) from
   <https://download.documentfoundation.org/libreoffice/stable/>, e.g. for
   26.2.4/x86_64: `LibreOffice_26.2.4_Linux_x86-64_rpm.tar.gz` and
   `LibreOffice_26.2.4_Linux_x86-64_rpm_sdk.tar.gz`. Extract each `.rpm`
   inside them with `rpm2cpio`/`cpio` into a prefix directory — no root
   required:

   ```bash
   tar xzf LibreOffice_*_rpm.tar.gz LibreOffice_*_rpm_sdk.tar.gz
   mkdir -p ~/opt
   for rpm in LibreOffice_*_rpm/RPMS/*.rpm LibreOffice_*_rpm_sdk/RPMS/*.rpm; do
     rpm2cpio "$rpm" | cpio -idm --no-absolute-filenames -D ~/opt
   done
   mv ~/opt/opt/libreoffice* ~/libreoffice26.2   # adjust to the extracted version
   export LO_HOME=~/libreoffice26.2
   ```

   This lays out the same `program/` and `sdk/bin/` tree the Windows install
   has (`unoidl-write`, `javamaker`, `types.rdb`, `program/classes/*.jar`).

3. **Java vendor allow-list.** LibreOffice only loads a JVM whose
   `java.vendor` appears in `$LO_HOME/program/javavendors.xml` (Sun, Oracle,
   IBM, Blackdown, BEA, Azul, Amazon by default). A stock Temurin/Adoptium
   build reports vendor `Temurin`, which is **not** on that list — `unopkg`
   will fail with `CannotRegisterImplementationException: Could not create
   Java implementation loader` when installing the extension. Add an entry
   for it in your local `javavendors.xml` (this file lives inside your own
   LibreOffice install, not a system-shared one, so editing it is safe):

   ```xml
   <vendor name="Temurin">
     <minVersion>1.8.0</minVersion>
   </vendor>
   ```

   Insert it next to the other `<vendor>` entries, inside `<vendorInfos>`.
   (If your JDK came from your distro's package manager, its vendor is
   usually already on the list and this step is unnecessary.)

Confirm the tools resolve:

```bash
"$LO_HOME/sdk/bin/unoidl-write"          # prints usage
"$LO_HOME/sdk/bin/javamaker"             # prints usage
"$JAVA_HOME/bin/javac" -version          # any 8+
```

## 2. No API key needed

The World Bank Indicators API is open — no signup, no key, no rate-limit
headers to manage. Skip straight to building.

## 3. Build the .oxt

### Linux / macOS

```bash
export JAVA_HOME=~/jdks/jdk8u<version>   # or wherever your JDK 8 lives
export LO_HOME=~/libreoffice26.2         # or wherever LibreOffice + SDK live
./build.sh
# or pass paths explicitly instead of the env vars:
./build.sh --jdk ~/jdks/jdk8u<version> --libreoffice ~/libreoffice26.2
```

This produces `build/WorldBank.oxt` via five steps:

```
1. unoidl-write  idl/**                    -> build/types/XWorldBank.rdb (UNO type library)
2. javamaker     build/types/XWorldBank.rdb -> build/gen/**.class         (Java bindings)
3. javac         src/**.java + bindings    -> build/classes/**.class
4. jar           classes + bindings        -> build/oxt/worldbank.jar     (+ RegistrationClassName)
5. zip           staging tree              -> build/WorldBank.oxt
```

Two JDK-8-specific quirks it works around, in case you're compiling by hand:

- **`javac --release 8` doesn't exist on JDK 8 itself** (the flag was added
  in JDK 9); `build.sh` detects a `1.x` `javac -version` and falls back to
  `-source 8 -target 8`, which is equivalent for a straight JDK-8 build.
- **`jar` on JDK 8 can reject duplicate directory entries** when packaging
  two class trees that share a package path (`com/example/worldbank/`
  appears in both the compiled classes and the generated UNO bindings),
  throwing `java.util.zip.ZipException: duplicate entry: com/`. `build.sh`
  merges both trees into one staging directory first, then jars that single
  tree.

#### The equivalent commands by hand

```bash
LO="$LO_HOME"
export PATH="$LO/program:$PATH"

# 1. IDL -> UNO type library
"$LO/sdk/bin/unoidl-write" "$LO/program/types.rdb" idl build/types/XWorldBank.rdb

# 2. type library -> Java bindings
"$LO/sdk/bin/javamaker" -nD -Gc -O build/gen -X "$LO/program/types.rdb" build/types/XWorldBank.rdb

# 3. compile (JDK 9+: --release 8; JDK 8 itself: -source 8 -target 8)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -cp "build/gen:$LO/program/classes/*" \
  -d build/classes $(find src -name '*.java')

# 4. merge classes + bindings (avoids the JDK-8 jar duplicate-entry issue), then jar
mkdir -p build/jarstage
cp -r build/classes/. build/jarstage/
cp -r build/gen/. build/jarstage/
"$JAVA_HOME/bin/jar" cfm build/oxt/worldbank.jar registration/MANIFEST.MF -C build/jarstage .

# 5. stage config/types/manifest, then zip the four entries into build/WorldBank.oxt
#    (types/XWorldBank.rdb, worldbank.jar, config/CalcAddIns.xcu, description.xml, META-INF/manifest.xml)
```

### Windows

A PowerShell port of `build.sh` is not included in this release; the by-hand
commands above translate directly (swap `$LO/sdk/bin/…` for
`…\sdk\bin\….exe`, `:` path separators for `;`, and `find`/`$()` for
`Get-ChildItem -Recurse`).

## 4. Install into LibreOffice

Close LibreOffice first, then use `unopkg`:

```bash
"$LO_HOME/program/unopkg" add --force build/WorldBank.oxt
# list / remove:
"$LO_HOME/program/unopkg" list
"$LO_HOME/program/unopkg" remove com.example.worldbank
```

You can also install by double-clicking `build/WorldBank.oxt` (opens the
Extension Manager). Restart LibreOffice afterwards.

## 5. Try it

```
=WBVALUE("US"; "NY.GDP.MKTP.CD"; 2020)
=WBLATEST("US"; "NY.GDP.MKTP.CD")          (array formula: Ctrl+Shift+Enter)
=WBSERIES("US"; "NY.GDP.MKTP.CD"; 2015; 2020)   (array formula)
=WBMETA("NY.GDP.MKTP.CD")
```

See the README's "Try it" section for the full list.

## Troubleshooting

- `unoidl-write` / `javamaker` "not found" → pass the right
  `--libreoffice` path; the SDK must be installed (it is a separate
  download from LibreOffice on some platforms).
- Functions show `#NAME?` → the extension isn't registered; confirm with
  `unopkg list` and restart LibreOffice.
- A cell shows `#ERR` persistently → call `=WBLASTERROR()` for the detail
  message; common causes are an invalid indicator/country code or a
  transient network error. The add-in retries automatically after ~15
  seconds, so recalculating (F9) after a short wait often clears it.
- A cell shows `#NOT_FOUND` → the indicator/country pairing is valid but
  has no data (or every value in the requested year range is null). Try a
  wider `WBSERIES` range or a different country.
- `unopkg add` fails with `CannotRegisterImplementationException: Could not
  create Java implementation loader` (Linux/macOS) → your JDK's vendor isn't
  in `$LO_HOME/program/javavendors.xml`'s allow-list — see the Java vendor
  allow-list note in Prerequisites.
