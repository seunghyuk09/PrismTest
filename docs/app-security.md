# PrismScope 앱 보안 설계

사내 검사용 앱이지만 **제품을 촬영하는 앱**이다. 촬영물이 밖으로 나가지 않는다는 것을
말로 주장하는 대신 **구조적으로 불가능하게 만들고, 빌드마다 기계가 검증**한다.

## 위협 모델

| 위협 | 대응 |
|---|---|
| 검사 이미지·데이터가 외부로 유출된다 | **INTERNET 권한 제거.** 통신 경로 자체가 없다 |
| 앱이 카메라 외의 것을 수집한다 | 권한이 카메라 하나뿐. 금지 권한 목록을 CI 가 검사 |
| 다른 앱이 프로세스 메모리를 읽는다 | 릴리스 빌드(`debuggable=false`) |
| 검사 이미지가 클라우드로 새어나간다 | `allowBackup=false` |
| 전송 중 APK 가 변조된다 | SHA-256 공개 + GitHub HTTPS + 빌드 출처 증명 |
| 제3자가 가짜 업데이트를 배포한다 | 서명 키 고정, 인증서 지문 공개 |
| 앱이 실제로 뭘 하는지 모른다 | 전체 소스 공개 |

## 1. 네트워크 차단 — 이 앱의 핵심 안전 속성

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

`tools:node="remove"` 는 **의존 라이브러리가 INTERNET 을 병합하더라도 최종 APK 에서
제거**한다. 안드로이드에서 INTERNET 권한이 없는 앱은 소켓을 열 수 없다.
즉 촬영한 프리즘 이미지가 외부로 나갈 **경로가 물리적으로 존재하지 않는다.**

같은 방식으로 제거하는 권한: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`READ_PHONE_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.

**요구 권한은 `CAMERA` 하나뿐이다.**

## 2. 릴리스 빌드

| 항목 | 값 | 이유 |
|---|---|---|
| `debuggable` | **false** | 디버그 빌드는 다른 앱·ADB 가 프로세스에 붙어 메모리를 읽을 수 있다. Play Protect 가 경고하는 정당한 이유이기도 하다 |
| `allowBackup` | false | 검사 이미지가 Google 백업으로 넘어가지 않게 |
| `hasFragileUserData` | false | 삭제 시 데이터 잔존 방지 |
| `usesCleartextTraffic` | false | 네트워크가 없지만 이중 방어 |
| `dependenciesInfo` | 미포함 | APK 내 불투명 암호화 블록 제거 — 제3자 검증 가능성 확보 |
| `minifyEnabled` | **false (당분간)** | R8 축소는 실기 검증 후 켠다. 검증 전에 켜면 문제 원인 추적이 어려워진다 |

## 3. 서명

**릴리스 키스토어는 저장소에 두지 않는다.** GitHub Secrets 에 base64 로 넣고
CI 가 빌드 시점에만 복원한다.

| 시크릿 | 내용 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | 키스토어 파일을 base64 인코딩한 값 |
| `RELEASE_KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `RELEASE_KEY_ALIAS` | 키 별칭 |
| `RELEASE_KEY_PASSWORD` | 키 비밀번호 |

시크릿이 없으면 빌드는 **디버그 키로 서명하고 그 사실을 릴리스 노트에 명시**한다.
조용히 넘어가지 않는다 — 안전하다고 오인하는 것이 안전하지 않은 것보다 나쁘다.

### 키스토어 만드는 법 (로컬 PC 에서 1회)

```bash
keytool -genkeypair -v -keystore prismscope-release.jks \
  -alias prismscope -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<강한-비밀번호>' -keypass '<강한-비밀번호>' \
  -dname "CN=PrismScope, O=<회사명>, C=KR"

base64 -w0 prismscope-release.jks > keystore.b64   # 이 내용을 시크릿에 붙여넣기
```

GitHub → 저장소 → **Settings → Secrets and variables → Actions → New repository secret**
에서 위 네 개를 등록한다. **키스토어 원본 파일은 안전한 곳에 보관하라.**
잃어버리면 같은 서명으로 업데이트를 낼 수 없어 기존 앱을 지우고 새로 깔아야 한다.

## 4. 검증 — 주장이 아니라 기계 검사

CI 는 빌드한 APK 를 `aapt2` 와 `apksigner` 로 직접 뜯어 아래를 확인한다.
**하나라도 어긋나면 빌드를 실패시켜 배포되지 않는다.**

- 금지 권한(INTERNET, 위치, 마이크, 연락처, SMS, 전화상태)이 하나도 없을 것
- `application-debuggable` 플래그가 없을 것
- 서명이 유효할 것

통과하면 릴리스 노트에 **APK SHA-256** 과 **서명 인증서 SHA-256** 을 게시한다.

- APK 해시가 같으면 → 전송 중 변조되지 않았다
- 인증서 해시가 이전과 같으면 → 같은 사람이 만든 업데이트다. **달라지면 의심해야 한다**

추가로 `actions/attest-build-provenance` 로 **이 저장소의 이 커밋에서 이 워크플로가
빌드했다는 서명된 증명**을 첨부한다.

## 5. 왜 경고가 뜨는가 (그리고 왜 그게 정상인가)

| 장치 | 동작 | 성격 |
|---|---|---|
| **삼성 자동 차단(Auto Blocker)** | 스토어 외 설치를 **전면 차단** | 설치 불가의 실제 원인. 잠시 꺼야 한다 |
| **Play Protect** | 스토어 미등록 앱에 경고 | "악성코드 발견"이 아니라 "검증 이력 없음" |
| **출처를 알 수 없는 앱** | 브라우저별 설치 허용 필요 | 정상 절차 |

이들은 **앱이 나쁘다는 판정이 아니라 "구글이 검사한 적 없다"는 사실의 표시**다.
사이드로드하는 모든 앱에 동일하게 뜬다.

경고를 없애는 정공법은 하나뿐이다 — **Google Play 내부 테스트 트랙 배포**.
Play Console 계정(최초 $25)이 필요하고, 등록하면 스토어를 통해 설치되어
경고도 자동 차단도 겪지 않으며 업데이트도 자동으로 내려간다.
검사 폰이 서너 대를 넘어가면 그쪽이 낫다.

## 6. 사용자가 직접 확인하는 법

앱 설치 후 **설정 → 애플리케이션 → PrismScope → 권한**에서 카메라 외에
아무것도 없는지 볼 수 있다. 네트워크 권한이 없는 앱은 데이터 사용량 항목 자체가
나타나지 않는다.
