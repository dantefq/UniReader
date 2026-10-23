# Corresponding source and component provenance

UniReader 1.2.1 application and changes are supplied under GNU AGPL 3 or later. The retained LibreOffice Java JNI sources remain under MPL 2.0, as stated in their headers. See LICENSE-AGPL-3.0.txt, LICENSE-LibreOffice-MPL.txt and component notices. No proprietary source or code from One Read.apk is used.

## MuPDF 1.28.5

Unmodified official binary: https://maven.ghostscript.com/com/artifex/mupdf/fitz/1.28.5/fitz-1.28.5.aar

SHA256: e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e

Corresponding source is supplied in the complete UniReader-1.2.1-source.zip attached to the v1.2.1 GitHub release, in third_party/mupdf-1.28.5-source.tar.gz, including third-party source, and third_party/mupdf-android-fitz-1.28.5.zip with the Android build scripts. Neither native source nor Java bindings were modified.

Official source tarball: https://github.com/ArtifexSoftware/mupdf-downloads/releases/download/1.28.5/mupdf-1.28.5-source.tar.gz

Source tarball SHA256: 98a5c10cda20c3992cdf76ff6b2a1149c32bd79cc796d3f703230b1185b7e934

Android wrapper: https://github.com/ArtifexSoftware/mupdf-android-fitz/tree/1.28.5

Unpack wrapper and place the full MuPDF source in wrapper/libmupdf. The wrapper README/Makefile documents its build/publication commands. Build uses NDK 28.2.13676358, AGP 8.5.2, compile SDK 33, target 36 and min 21; our application min is 26. See the supplied upstream files for exact settings and third-party notices.

## LibreOfficeKit 26.2.6.3

Unmodified binaries/resources are fetched from the exact F-Droid APKs and validated by tools/fetch-engine.ps1. tools/configure-engine.ps1 contains all application configuration edits. Application code fixes the private font cache path at runtime.

Official corresponding source distributions:

- https://f-droid.org/repo/org.documentfoundation.libreoffice_131_src.tar.gz
- https://f-droid.org/repo/org.documentfoundation.libreoffice_129_src.tar.gz
- https://download.documentfoundation.org/libreoffice/src/26.2.6/
- Build metadata: https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.documentfoundation.libreoffice.yml

No signing keys, passwords, imported personal documents or generated build caches are included.
