# Eora

**어라? 하고 발견한 문제를, 재현 가능한 제보로.**

**Eora** (어라) comes from a Korean interjection used when you notice something unexpected.

STG Android 앱에서 문제 화면과 재현 정보를 모아, 검토한 뒤 이슈 트래커에 전달하기 위한 **커스터마이징 가능한 QA 라이브러리 구상**입니다.

> 현재 상태: 0.2.0 개발자 프리뷰입니다. 실제 Jira 업로더·게이트웨이는 포함하지 않으므로 호스트가 `IssueUploader`를 구현해야 합니다(아래 연동 가이드). 0.x 동안 공개 API가 바뀔 수 있습니다. 결정이 필요한 항목은 [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md)에 있습니다.

## 설치

```kotlin
dependencies {
    // STG(QA) 변형에만 실제 구현, 배포용 변형에는 같은 API의 no-op
    "stgImplementation"("io.github.easyhooon:eora:0.2.0")
    "prodImplementation"("io.github.easyhooon:eora-noop:0.2.0")
    // 선택: 화면 녹화(0.2.0부터). 아래 '필요한 권한' 참고
    "stgImplementation"("io.github.easyhooon:eora-recording:0.2.0")
}
```

변형 이름은 호스트의 flavor/build type에 맞춥니다. minSdk 26, Jetpack Compose와 `androidx.ink` `1.1.0-alpha07`을 사용합니다.

## 연동 가이드

1. `IssueUploader`를 구현합니다. 조직이 운영하는 게이트웨이를 호출하고, Jira 토큰·시크릿은 APK에 넣지 않습니다.
   - `createIssue`: 새 이슈 생성. 게이트웨이는 `reportId` + `revision`으로 중복 생성을 막아야 합니다.
   - `upload`: 생성된 이슈에 파일 하나 첨부.
   - 응답을 잃었을 수 있으면 `Unknown`을, 확실히 실패했을 때만 `Failed`를 돌려줍니다. 두 함수는 백그라운드 스레드에서 호출됩니다.
2. STG 소스셋에서만 설치합니다(배포용 코드에 업로더·설정이 들어가지 않게).

```kotlin
// src/stg/.../QaSetup.kt
Eora.install(
    application,
    EoraConfig(
        uploader = MyGatewayUploader(),
        screenIdentifierProvider = { activity -> activity.javaClass.simpleName },
    ),
)
```

3. 흔들거나 `Eora.startReport()`(수동 진입)를 호출합니다. 초안 목록은 `Eora.openDrafts()`입니다.
4. (선택) `eora-recording`을 추가하면 흔들기 메뉴에 "화면 녹화"가 생깁니다. `Eora.startRecording()`으로도 시작할 수 있습니다. 다시 흔들거나, 알림의 정지 버튼을 누르거나, 60초가 지나면 멈춥니다. 녹화본(720p급 H.264, 오디오 없음)은 영상이 첨부된 초안으로 열립니다. 영상은 가림 처리를 할 수 없으니, 민감한 화면이 담겼다면 첨부에서 빼도록 안내합니다.

개발 확인용으로 `LocalExportUploader`(파일을 앱 전용 외부 저장소에 쓰는 PoC)를 쓸 수 있습니다. QA 사용자용 흐름에는 쓰지 않습니다. 전체 예시는 `sample` 모듈에 있습니다.

## 필요한 권한

- `eora` 라이브러리는 권한을 선언하지 않습니다. 가속도계(shake), `PixelCopy` 캡처, Photo Picker, 앱 전용 저장소는 권한이 필요 없습니다.
- 호스트 앱은 실제 업로더(게이트웨이 호출)를 연결할 때 `INTERNET`만 선언하면 됩니다.
- release용 `eora-noop`은 권한·화면·센서·파일 접근이 없습니다. `./gradlew checkReleaseLeak`이 샘플 prodRelease APK에서 이를 검사합니다.
- 화면 녹화는 opt-in 모듈 `eora-recording`에 있습니다. 이 모듈을 추가한 변형에만 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`이 합쳐집니다. 둘 다 설치 시 부여되는 권한이라 사용자에게 묻지 않습니다. 대신 녹화할 때마다 시스템 동의 창이 뜹니다. 마이크·알림 권한은 요청하지 않습니다.

## 빌드와 검증

```bash
./gradlew :eora:testDebugUnitTest checkEoraApiParity checkReleaseLeak
```

샘플 앱 실행: `./gradlew :sample:installStgDebug` 후 화면을 흔들거나 "Report a problem"을 누릅니다. 제목 앞에 `[lost]`(이슈 생성 응답 유실), `[partial]`(스크린샷 응답 유실), `[video]`(영상 거부), `[403]`(권한 없음)을 붙이면 상태별 흐름을 볼 수 있고, 붙이지 않으면 성공합니다.

## 해결하려는 문제

QA 중 화면을 캡처하고, 문제 위치를 표시하고, 기기·앱 버전을 적은 뒤 Jira 티켓에 첨부하는 작업은 여러 앱을 오가게 됩니다. 이 과정을 STG 앱 안의 짧은 흐름으로 연결하고, 개발자가 재현에 필요한 맥락을 함께 받도록 하는 것이 목표입니다.

## 제안하는 사용 흐름

1. STG 앱에서 문제를 발견하면 기기를 흔든다(shake-to-open).
2. 제보 UI가 뜨기 전 원래 화면을 캡처하고, 편집기에서 원·화살표·텍스트로 문제 위치를 표시한다.
3. 민감한 영역을 불투명하게 가리고, 필요한 경우 이미 녹화한 영상을 선택한다.
4. 앱 버전, OS, 기기 모델, 안전하게 가공된 화면 식별자와 재현 설명을 확인한다.
5. 새로 만들 Jira 이슈의 제목·본문과 첨부 파일을 최종 검토한다.
6. 사용자가 업로드를 눌렀을 때만 전송하고, 파일별 성공 여부를 확인한다.

Jira 연동은 제보마다 **새 이슈를 생성하고 그 이슈에 파일을 첨부**하는 방식입니다(2026-10-20 결정, OPEN_QUESTIONS #15). 기존 이슈를 수정하거나 기존 첨부를 덮어쓰지 않습니다.

## MVP 범위

- 상시 떠 있는 UI 없이 STG에서 shake-to-open, 제보 UI 표시 전 화면 캡처
- foreground 생명주기 제어, 감도·쿨다운 설정, 수동 호출 대체 경로
- 이미지 주석: 원, 화살표, 텍스트, 실행 취소
- 불투명 가림 처리와 최종 결과 미리보기
- 허용 목록 기반의 최소 재현 메타데이터
- 기존 이미지·영상 선택, 크기 제한, 로컬 임시 보관
- 사용자 확인 후 새 이슈를 만들고 첨부하는 교체 가능한 업로더
- 재시도 시 같은 보고서를 식별하고 부분 업로드를 복구하는 설계

사용자가 쓰는 MVP는 앱 안에서 Jira 이슈를 만들고 직접 첨부하는 흐름까지 포함합니다. 로컬 파일 내보내기는 개발 검증용 PoC일 뿐 사용 가능한 MVP로 취급하지 않습니다. QA 담당자에게 파일 이동이나 CLI 사용을 요구하지 않습니다. 인증 방식이 정해지기 전에는 Jira 연동 완료 일정을 약속하지 않으며, 앱 내 영상 녹화는 후속 범위입니다.

## 커스터마이징 방향

진입 트리거, 캡처 방식, 메타데이터 제공자, 가림 처리, 이슈 업로더를 교체 가능한 경계로 설계합니다. Jira 외의 트래커도 연결할 수 있는 구조를 목표로 하되, 첫 사용자 흐름은 앱 안에서 Jira 직접 첨부입니다. 아직 공개 API 서명이나 모듈 이름은 확정하지 않았습니다.

## 화면을 가리지 않는 진입

shake는 STG 호스트 앱이 foreground일 때만 감지합니다. 감지되면 원래 화면을 먼저 캡처한 뒤 제보 UI를 열며, 이미 제보 중이면 중복 호출을 무시합니다. 센서가 없거나 흔들기 어려운 경우 호스트의 수동 호출 경로를 사용합니다. shake는 촬영할 로컬 초안을 여는 동작이며 녹화·외부 전송 동의가 아닙니다.

[Dari](https://github.com/easyhooon/dari)의 shake 진입과 desktop-only 분리를 참고합니다. Dari를 desktop-only로 설정한 STG에서 Eora가 기기 내 QA 진입을 담당하는 구성이 가능하도록 설계하되, Dari를 필수 의존성으로 두지 않습니다. 연동 호환성은 아직 검증 전입니다.

## Developer-mode MVP

호스트 프런트엔드 코드를 먼저 분석해 실제 사용하는 저장소 키·타입·기본값을 확인하고, QA에 필요한 소수 항목만 등록합니다. 기존 저장소 값을 읽고, boolean은 켜기/끄기, 다른 타입은 읽기 전용 표시 또는 호스트가 정한 버튼 동작으로 제한합니다. 초기화는 값 삭제 또는 명시적 기본값 복원으로 구분합니다. 별도의 플래그 저장소를 중복으로 만들지 않습니다.

개발자 모드는 선택적 확장으로, Eora는 기본 버튼·Boolean 토글·현재값·확인·실행결과 UI만 제공하고 읽기·변경·초기화는 호스트 콜백으로 연결하는 가장 작은 구조를 목표로 합니다. STG origin과 키 허용 목록을 적용하며 임의 JavaScript 편집기는 제공하지 않습니다. 실제 키와 저장 방식은 아직 확인하지 않았습니다. API 호출로 서버 플래그·데이터를 바꾸는 기능은 이 로컬 MVP에서 제외합니다. 인증·토큰 또는 서버 동작을 유발할 수 있는 로컬 값도 토글하지 않습니다.

앱 내 Jira 직접 첨부 MVP와 이 작은 로컬 개발자 모드를 함께 설계합니다. [구현 인계 문서](docs/IMPLEMENTATION_HANDOFF.md)에 구현 순서와 검증 기준을 정리했습니다. 기존 봇 서버 활용과 서버 QA 액션은 별도 후속 선택지입니다.

## 안전한 기본값

- STG 빌드에만 실제 구현을 연결하고, 배포용 변형에는 제거 또는 no-op 구현을 사용합니다.
- Jira API 토큰·클라이언트 시크릿을 APK에 포함하지 않습니다.
- 전체 URL, 쿼리 문자열, 쿠키, 인증 헤더, 사용자 입력, 건강 정보 등은 기본 수집하지 않습니다.
- 화면 식별자는 호스트 앱이 제공한 안전한 값만 허용합니다.
- STG 데이터도 민감할 수 있으므로 이미지·영상은 별도 검토합니다.
- 업로드 실패나 네트워크 복구만으로 비공개 자료를 자동 재전송하지 않습니다.
- 로컬 임시 파일의 보관 기한·용량 상한과 사용자의 삭제 동작을 둡니다.

## 문서

- [기능과 완료 기준](docs/FEATURES.md)
- [아키텍처와 신뢰 경계](docs/ARCHITECTURE.md)
- [단계별 로드맵](docs/ROADMAP.md)
- [구현 인계와 검증 기준](docs/IMPLEMENTATION_HANDOFF.md) (저장소에 없음, OPEN_QUESTIONS #1)
- [미결정 사항과 임시 기본값](docs/OPEN_QUESTIONS.md)

## 미결정 사항

Jira Cloud/Data Center 대상, 사내 인증 방식, 게이트웨이 API 계약, 리포터·담당자 지정 방식은 결정 전입니다. 임시 기본값과 함께 [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md)에 정리했습니다.

## 공식 참고 자료

- [Android PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photopicker)
- [Jira Cloud 첨부 API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-attachments/)

문서는 위 API를 설계 검토에 참고합니다. 제품 지원 범위나 실제 기기 호환성을 보증하지 않습니다.

## License

```
Copyright 2026 easyhooon

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
