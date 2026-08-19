# PrismScope 개인정보 처리방침 / Privacy Policy

최종 수정일: 2026-08-19

PrismScope(이하 "본 앱")는 광학 프리즘의 외관 검사를 위한 사내 측정 도구입니다.
아래 내용은 본 앱이 실제로 수행하는 동작을 그대로 기술한 것이며,
앱 소스 코드 전체가 공개되어 있어 누구든 확인할 수 있습니다.

---

## 1. 수집하는 개인정보

**본 앱은 어떠한 개인정보도 수집하지 않습니다.**

본 앱은 이름, 이메일, 전화번호, 위치, 연락처, 기기 식별자, 사용 기록 등
어떤 개인정보도 수집·저장·전송하지 않습니다.

## 2. 네트워크 통신

**본 앱은 네트워크에 접속할 수 없습니다.**

본 앱은 Android `INTERNET` 권한을 선언하지 않습니다.
해당 권한이 없는 애플리케이션은 운영체제 수준에서 네트워크 소켓을 열 수 없으므로,
본 앱이 어떤 데이터든 외부로 전송하는 것은 기술적으로 불가능합니다.

이 사실은 배포 전 자동 검사로 매 빌드마다 검증되며,
검사에 실패한 빌드는 배포되지 않습니다.

광고, 분석 도구(analytics), 충돌 보고 도구(crash reporting), 추적 SDK를
일절 포함하지 않습니다.

## 3. 카메라 사용

본 앱은 `CAMERA` 권한 하나만을 요구합니다.

- 용도: 검사 대상 프리즘의 촬영 및 실시간 밝기·대비 측정
- 촬영된 이미지는 **사용자 기기 내부 저장소에만** 저장됩니다
  - 이미지: `Pictures/PrismScope`
  - 측정 로그: `Documents/PrismScope/prismscope_log.csv`
- 저장된 파일은 외부로 전송되지 않으며, 사용자가 직접 파일 앱이나 USB를 통해
  옮기지 않는 한 기기를 벗어나지 않습니다

## 4. 데이터 백업

본 앱은 `allowBackup="false"` 로 설정되어 있어,
저장된 검사 이미지 및 로그가 Google 클라우드 백업이나 기기 간 전송으로
복사되지 않습니다.

## 5. 제3자 제공

제공하는 제3자가 없습니다. 전송 자체가 불가능하기 때문입니다.

## 6. 데이터 보관 및 삭제

모든 데이터는 사용자 기기에만 존재합니다.
갤러리 또는 파일 관리자에서 해당 폴더를 삭제하거나,
앱을 제거함으로써 언제든 삭제할 수 있습니다.

## 7. 아동 대상 여부

본 앱은 산업 현장의 품질검사 용도로 제작되었으며 아동을 대상으로 하지 않습니다.

## 8. 소스 코드

본 앱의 전체 소스 코드는 아래에서 확인할 수 있습니다.

https://github.com/seunghyuk09/PrismTest

## 9. 문의

seunghyuk679@gmail.com

---

# Privacy Policy (English)

Last updated: 2026-08-19

PrismScope is an in-house measurement tool for the visual inspection of
optical prisms.

**No personal data is collected.** The app declares no `INTERNET`
permission; an Android application without that permission cannot open a
network socket, so transmitting any data off the device is technically
impossible. This is verified automatically on every build, and builds that
fail the check are not published.

The app requests one permission, `CAMERA`, used to photograph the prism
under inspection and compute brightness and contrast statistics. Captured
images and measurement logs are written only to the device's own storage
(`Pictures/PrismScope` and `Documents/PrismScope`). Backup is disabled, so
those files are not copied to cloud backup or device-to-device transfer.

The app contains no advertising, analytics, crash reporting, or tracking
SDKs, and shares no data with any third party. All data can be deleted by
removing the folders or uninstalling the app.

Full source: https://github.com/seunghyuk09/PrismTest
Contact: seunghyuk679@gmail.com
