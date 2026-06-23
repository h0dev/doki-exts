# Usagi Plugin

This library provides a collection of manga parsers for convenient access to manga available on the web. It can be used in
JVM and Android applications. This project is based on [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers)
and depends on [core-parsers](https://github.com/UsagiApp/core-parsers).

![Sources count](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FUsagiApp%2Fplugins%2Frefs%2Fheads%2Fmain%2F.github%2Fsummary.yaml&query=total&label=manga%20sources&color=%23E9321C) ![License](https://img.shields.io/github/license/KotatsuApp/Kotatsu)

## Build

```bash
./gradlew jar          # Build plugin JAR
./gradlew buildJar     # Build + DEX for Android
```

## Usage

1. Download the latest JAR from [GitHub Releases](https://github.com/UsagiApp/plugins/releases)

2. Usage in code

   ```kotlin
   val parser = mangaLoaderContext.newParserInstance(MangaParserSource.CMANGA)
   ```

   `mangaLoaderContext` is an implementation of the `MangaLoaderContext` class.

   Note that the `MangaParserSource.DUMMY` parsers cannot be instantiated.

## Credits

- [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) — upstream project with 1400+ parsers
- [UsagiApp/core-parsers](https://github.com/UsagiApp/core-parsers) — parser framework dependency

## DMCA disclaimer

The developers of this application have no affiliation with the content available in the app. It is collected from
sources freely available through any web browser.
