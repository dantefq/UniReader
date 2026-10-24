# LibreOfficeKit provenance

Pinned release: **26.2.6.3**. APK signatures were verified with Android `apksigner`. Both APKs have the same signing certificate SHA-256:

`af71ba344cab259fa625f079ff520f511d17a372bb2e8fa27532791bd5fea8cb`

| Build | Download | SHA-256 |
|---|---|---|
| F-Droid 131, arm64-v8a | https://f-droid.org/repo/org.documentfoundation.libreoffice_131.apk | `751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475` |
| F-Droid 129, x86_64 | https://f-droid.org/repo/org.documentfoundation.libreoffice_129.apk | `cfaf9eea5fd695c449e92eb10cc0b1617ec51d068345068cea9b7e1c67968df5` |

The x86_64 file was fetched from the F-Droid mirror `https://ftp.fau.de/fdroid/repo/` using the F-Droid user agent. A separately downloaded F-Droid copy can be checked against the pinned digest. Only `lib/` and engine `assets/` (excluding dexopt profiles) were extracted. No original Android classes or UI resources were copied. Java JNI binding sources are pinned to the matching LibreOffice tag.

Sources and licenses:

- https://github.com/LibreOffice/core/tree/libreoffice-26.2.6.3
- https://f-droid.org/repo/org.documentfoundation.libreoffice_131_src.tar.gz
- https://f-droid.org/repo/org.documentfoundation.libreoffice_129_src.tar.gz
- https://f-droid.org/repo/org.documentfoundation.libreoffice_131.log.gz
- https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.documentfoundation.libreoffice.yml
- https://raw.githubusercontent.com/LibreOffice/core/libreoffice-26.2.6.3/readlicense_oo/license/license.xml
- https://www.libreoffice.org/about-us/credits/

Native binaries are unmodified. `main.xcd` security settings are changed and their modified source is distributed here. The native engine source tree is not duplicated in this small project archive; corresponding upstream source archives are linked above.

QuietRead 1.1 also sets Calc Input/WarnActiveSheet=false. Its native UI explicitly warns that CSV exports only the current sheet, replacing the unavailable LibreOffice desktop dialog. DisableMacrosExecution and DisableOLEAutomation remain true; DisableActiveContent retains the upstream false default, as authorized by the user for embedded charts.

Independent legacy-format test fixtures:

- https://github.com/apache/poi/blob/trunk/test-data/document/simple.doc
- https://github.com/apache/poi/blob/trunk/test-data/spreadsheet/Simple.xls
- https://github.com/apache/poi/blob/trunk/test-data/slideshow/basic_test_ppt_file.ppt
- Apache POI license: https://www.apache.org/licenses/LICENSE-2.0
