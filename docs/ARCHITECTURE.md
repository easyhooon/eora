# Architecture

아래 구성과 이름은 제안이며 구현된 모듈·공개 API가 아닙니다. 최초 구현은 작은 단위로 유지하고, 실제 교체 수요가 확인된 경계만 분리합니다.

## 구성 경계

1. **Host app / Trigger**: STG 수동 진입점과 안전한 화면 식별자를 제공합니다. 선택 가능한 ShakeTrigger 어댑터가 foreground에서만 이벤트를 보내며, 캡처와 업로드 구현에는 의존하지 않습니다.
2. **CaptureProvider**: 앱 화면을 캡처합니다. View·Compose·WebView별 검증 결과에 따라 구현을 선택합니다.
3. **MetadataProvider**: 허용 목록에 있는 필드만 수집합니다.
4. **Redactor / Annotation editor**: 불투명 가림과 주석을 적용한 최종 파일을 만듭니다.
5. **Report draft store**: 앱 전용 임시 저장소에서 초안·파일·전송 상태와 보관 한도를 관리합니다.
6. **Review UI**: 실제 전송 대상과 파일을 보여 주고 사용자의 전송 선택을 받습니다.
7. **IssueUploader**: 승인된 보고서를 이슈 트래커로 전달하는 교체 가능한 경계입니다. 사용자용 MVP는 앱 내 Jira 직접 첨부이며 로컬 내보내기는 개발 PoC에서만 사용합니다.
8. **Auth gateway**: 조직이 승인한 서버 측 인증·권한 확인·업로드 중계 경계입니다. 필수 운영 주체가 정해지기 전에는 가동 중인 서비스로 가정하지 않습니다.

## 데이터 흐름

사용자 shake 또는 수동 시작 → 활성 화면·단일 실행 확인 → 제보 UI 표시 전 캡처 → 제보 UI 표시/미디어 선택 → 최소 메타데이터 수집 → 주석·가림 → 최종 파일 생성 → 티켓·내용 검토 → 명시적 전송 → 파일별 결과 표시 → 임시 자료 정리.

검토 대상은 중간 캡처가 아니라 최종 전송 바이트와 메타데이터입니다. 검토 이후 변경이 있으면 승인 상태를 초기화합니다. 가림 전 원본과 불필요한 이미지 메타데이터가 최종 파일에 남지 않도록 확인합니다.

## Trigger와 화면 생명주기

Trigger는 제보 시작 요청만 전달합니다. coordinator 역할이 foreground 상태와 활성 호스트 화면을 다시 검사하고 단일 실행 잠금을 잡은 뒤 캡처를 요청합니다. 비동기 캡처 완료 시에도 같은 화면과 lifecycle이 유효한지 확인하며, 오래된 콜백으로 UI를 열지 않습니다. 센서 이벤트 원본은 저장하거나 전송하지 않습니다.

ShakeTrigger는 선택 가능한 어댑터입니다. 감도·쿨다운·중복 이벤트 처리를 조정할 수 있게 하며, 화면이 비활성화되면 구독과 감지 상태를 정리합니다. 수동 트리거에도 같은 단일 실행·검토·전송 경계를 적용합니다. 캡처 후 제보 UI를 열어 QA 화면 자체가 증거 이미지에 찍히지 않도록 합니다. 원래 화면이 사라졌다면 새 화면을 대신 찍었다고 숨기지 않고 취소 상태를 알립니다.

상시 화면 요소와 background 감지는 제공하지 않습니다. 흔들기로 시작하는 로컬 캡처를 사용자에게 안내하고 비활성화할 수 있게 합니다. 흔들림은 녹화 시작, 과거 화면 재생/상시 버퍼링, 외부 업로드 동의가 아닙니다.

### Dari를 함께 쓰는 경우

[Dari desktop 문서](https://github.com/easyhooon/dari/blob/main/documentation/content/docs/desktop.mdx)와 [DesktopOnlyGate](https://github.com/easyhooon/dari/blob/main/dari/src/main/kotlin/com/easyhooon/dari/DesktopOnlyGate.kt)를 확인했습니다. DESKTOP_ONLY 설정은 Dari의 알림·shake·런처 진입점을 비활성화합니다. non-debuggable 내부 빌드에서 desktop 연결을 허용하는 설정은 별도입니다. STG라는 이름만으로 자동 적용되는 규칙은 아닙니다.

Eora는 해당 STG 구성에서 독립적인 shake 진입을 담당하는 방향입니다. Dari 설치·연결을 요구하거나 Dari 설정을 자동 변경하지 않습니다. 두 도구의 shake가 모두 켜진 다른 구성에서는 호스트가 하나를 끄거나 트리거를 조정해야 합니다. 실제 동시 사용 테스트는 후속 검증 항목입니다.

[Dari ShakeDetector](https://github.com/easyhooon/dari/blob/main/dari/src/main/kotlin/com/easyhooon/dari/shake/ShakeDetector.kt)의 감지·쿨다운·센서 해제 구조를 참고하되 소스 복사, 고정 임계값 채택이나 동작 동일성을 전제하지 않습니다.

## 인증과 신뢰 경계

- Jira 토큰이나 OAuth 클라이언트 시크릿을 APK·리소스·BuildConfig·저장소에 넣지 않습니다.
- 호스트 앱은 승인된 로그인 방식으로 게이트웨이를 호출하는 구성을 우선 검토합니다. 인증 방식은 아직 미정입니다.
- 게이트웨이는 인증 사용자에게 허용된 Jira 프로젝트·티켓인지 검증합니다. 서비스 계정의 넓은 권한을 모든 사용자에게 그대로 노출하지 않습니다.
- 서버가 받을 Jira 호스트·프로젝트·파일 유형·크기를 제한합니다. 클라이언트가 보낸 임의 URL로 파일을 전달하는 프록시가 되어서는 안 됩니다.
- 파일 내용, 토큰, 원시 메타데이터를 운영 로그에 남기지 않습니다. 필요한 진단 기록은 보고서 ID와 비민감 상태값으로 최소화합니다.
- 이 저장소는 자격 증명 발급·실서비스 연결을 포함하지 않습니다.

### 인증 방식은 결정 전

자체 상시 서버가 없는 구성과 중계 자체가 없는 구성은 다릅니다. 서버리스 함수도 비밀 저장소·호출자 인증·프로젝트 권한·토큰 교체·감사와 비용 관리가 필요한 서버 측 경계입니다. 서버리스라는 이유로 무로그인과 안전한 무기한 인증이 동시에 해결된다고 가정하지 않습니다.

Jira Cloud에서 검토할 선택지는 다음과 같습니다. Data Center에는 그대로 적용하지 않습니다.

- **서비스 계정 OAuth 2.0(client credentials / 2LO) + 게이트웨이**: 서버에 보관한 client secret으로 60분 유효 access token을 새로 발급받을 수 있습니다. API 토큰을 주기적으로 수동 교체하는 방식만이 유일한 선택지는 아닙니다. 조직의 서비스 계정 지원·관리 권한과 프로젝트 접근을 확인해야 하며, 비밀 폐기·유출 대응·운영자 교체 책임은 남습니다. [공식 서비스 계정 OAuth 안내](https://support.atlassian.com/user-management/docs/create-oauth-2-0-credential-for-service-accounts/)
- **사용자별 OAuth 2.0(3LO) + 게이트웨이**: 최초 사용자 동의 후 offline_access와 회전형 refresh token으로 평소 재로그인을 줄일 수 있습니다. refresh token은 90일 미사용 시 만료될 수 있고 철회·갱신 실패 시 재승인이 필요합니다. token 교환과 refresh에 client_secret이 필요하므로 Android 단독 공개 클라이언트/PKCE 지원을 전제하지 않습니다. [공식 3LO 안내](https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/)
- **조직이 승인한 API 토큰 기반 서버 연동**: 토큰 만료·교체 운영이 필요합니다. 공개 라이브러리가 QA 사용자에게 개인 API 토큰 수집을 요구하는 모델은 기본안으로 두지 않습니다.

기존 호스트 앱의 조직 로그인/세션을 게이트웨이가 검증해 재사용할 수 있다면 QA에 별도의 Jira 로그인 화면을 요구하지 않는 구성이 가능할 수 있습니다. 이것은 무인증 업로드를 뜻하지 않습니다. 서버에서 사용자·프로젝트·티켓 권한을 검증해야 하며, 서비스 계정 명의 첨부와 실제 제보자 기록을 구분해야 합니다.

공유 장기 토큰·client secret을 APK에 배포하거나, 공개 중계 URL만 알면 누구나 업로드하도록 만드는 방안은 제외합니다. 최종 인증 선택, 조직에서의 사용 가능 여부와 운영 책임이 정해질 때까지 Jira 연결은 설계상 미완료입니다.

## Report 모델: 개념 초안

제안 필드: reportId(UUID), createdAt, targetIssue, approvedMetadata, attachments, revision, transferStatus.

각 첨부에는 artifactId, 최종 파일의 digest, mimeType, sizeBytes와 remoteAttachmentId를 구분해 보관합니다. digest는 내용 변경 및 중복 복구에 활용할 수 있지만 인증·권한 검사를 대체하지 않습니다. 원본 파일명 자체도 민감할 수 있어 안전한 이름을 새로 부여하는 방안을 검토합니다.

이 모델은 확정된 데이터 클래스나 사용 가능한 SDK 예제가 아닙니다.

## 중복 방지와 부분 성공

Jira의 이슈 생성·첨부 추가 API 자체가 reportId 기반 멱등성을 제공한다고 가정하지 않습니다. 중복 방지는 게이트웨이가 담당할 별도 기능입니다. 이슈 생성 응답이 유실되면 클라이언트는 다시 만들지 않고 사용자에게 확인을 요청합니다.

- 게이트웨이는 사용자/조직 범위 안에서 보고서 ID와 revision을 등록하고 파일별 처리 결과를 기록합니다.
- 같은 키에 다른 대상·내용이 들어오면 기존 요청으로 처리하지 않고 충돌을 반환합니다.
- 완료된 파일은 remoteAttachmentId로 추적하고 사용자 재시도에서 제외합니다.
- 일부 성공 시 실패 또는 미확인 파일만 복구 대상으로 표시합니다.
- Jira는 받았지만 응답을 잃은 경우가 있습니다. 이때 무조건 재전송하지 않고 기존 첨부와 게이트웨이 기록을 대조합니다. 파일명·크기만으로 동일함을 확정하지 않습니다.
- 동일성을 확정할 수 없으면 ‘결과 확인 필요’ 상태를 유지하고 수동 확인을 요청합니다. exactly-once 업로드를 보장한다고 표현하지 않습니다.
- 보고서 편집은 새 revision과 재검토를 필요로 합니다. 성공한 이전 첨부를 자동 삭제하거나 덮어쓰지 않습니다.

MVP에서 이 복구를 구현하기 어렵다면 불확실한 전송은 재시도 버튼 대신 확인 필요 상태로 제한합니다. 작은 도구에서도 조용한 중복 첨부보다 명확한 상태가 우선입니다.

## 초안 상태와 보관

개념 상태: 작성 중 → 검토 가능 → 전송 중 → 성공 / 부분 성공 / 실패 / 결과 확인 필요.

사용자 취소·만료는 별도 상태입니다. 전송 취소 후에도 서버 또는 Jira에 도달한 파일이 있을 수 있으므로 상태를 조회해 안내합니다. 백그라운드 재전송이나 네트워크 복구 시 자동 전송은 초기 범위에서 제외합니다.

TTL과 용량 상한은 로컬 초안, 게이트웨이 임시 파일, 멱등성 기록을 구분해 정합니다. 게이트웨이의 원문 파일을 지워도 복구에 필요한 최소 상태 기록이 필요한지 검토합니다. Jira 첨부 보존 정책은 이 라이브러리의 로컬 TTL과 다릅니다.

## Variant 전략

STG 변형에 실제 QA 구현을 연결하고 배포용에는 제거 또는 no-op을 연결하는 방향입니다. 최종 Gradle 설정은 호스트 앱의 flavor/build type 체계에 맞춰 결정합니다.

검증 대상은 의존성 그래프, merged manifest, APK/AAB 내 QA 코드·자원·권한·서비스 포함 여부입니다. no-op에서도 파일 수집이나 네트워크 호출이 발생하지 않아야 합니다.

## 녹화 확장

MediaProjection은 사용자 동의 후 세션을 시작하며 종료 시 자원을 해제합니다. Android 14+ 단일 앱 공유와 전용 foreground service 요구를 고려합니다. 회전·잠금·사용자의 시스템 중지·프로세스 종료 시 부분 파일과 상태 정리도 필요합니다. 캡처 구현에 녹화를 섞기보다 후속 선택 기능으로 둡니다.

## 참고

- [Android PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Jira Cloud 첨부 API: 추가·조회·설정·삭제는 별도 연산](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-attachments/)

공식 API 제약과 본 문서의 권장 설계는 구분합니다. 게이트웨이·멱등성 모델은 이 프로젝트의 제안입니다.
