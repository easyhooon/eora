# Open Questions

설계 문서를 구현으로 옮기며 문서만으로 결정할 수 없었던 항목입니다. 답이 오기 전까지는 **임시 기본값**으로 구현하고, 해당 값은 설정으로 바꿀 수 있게 두었습니다. 답을 적어 주시면 코드와 문서를 맞춥니다.

## 막혀 있는 항목 (답 전까지 구현하지 않음)

1. **`docs/IMPLEMENTATION_HANDOFF.md`가 저장소에 없습니다.** README가 구현 순서·검증 기준 문서로 링크하지만 어떤 브랜치·커밋에도 없습니다. 로컬에만 있나요? 그동안은 ROADMAP Phase 1 범위와 "필수 테스트 목록 › 로컬·단위 테스트"를 완료 기준으로 사용합니다.
2. **Jira Cloud / Data Center 중 어느 쪽인가요?** 그리고 인증 방식(서비스 계정 2LO, 사용자별 3LO, 호스트 조직 로그인 재사용) 중 무엇을 택하나요?
3. **게이트웨이 API 계약을 누가 정하나요?** 엔드포인트·요청 형식·멱등성 키(`reportId` + `revision`)·응답 상태 코드가 정해져야 실제 업로더를 만들 수 있습니다. 지금은 `IssueUploader` 인터페이스와 개발용 로컬 내보내기(PoC)만 구현합니다. Jira REST를 앱에서 직접 호출하지 않습니다.
4. **라이선스를 정해 주세요.** Maven Central은 POM에 라이선스를 요구합니다. Dari와 같은 Apache-2.0으로 할까요? 정해지기 전에는 `publishToMavenLocal`까지만 검증합니다.

## 임시 기본값으로 진행 중인 항목

| # | 질문 | 임시 기본값 |
|---|---|---|
| 5 | Maven 좌표·패키지명 | `io.github.easyhooon:eora`, `io.github.easyhooon:eora-noop`, 패키지 `com.easyhooon.eora` (Dari 관례) |
| 6 | 최소 Android 버전 | `minSdk = 26` (PixelCopy Window 복사가 API 26부터) |
| 7 | 초기 UI 구현 방식·언어 | Jetpack Compose (Dari와 동일). 문구는 영어 기본 + `values-ko` 한국어(기기 언어를 따름). 언어와 상관없이 항상 한국어로 고정할까요? |
| 8 | 로컬 초안 TTL·용량 상한 | TTL 72시간, 초안 최대 20개, 총 200MB, 첨부 파일당 50MB |
| 9 | shake 감도·쿨다운 | 2.5G 이상 가속 4회/1초 이내, 쿨다운 2초 (모두 설정 가능) |
| 10 | 메타데이터 보고서 파일 형식 | 사람이 읽을 수 있는 `report.txt` (key: value). JSON이 필요한가요? |
| 11 | STG 변형 연결 방식 | 호스트가 `stgImplementation(eora)` / `releaseImplementation(eora-noop)`로 선택. 샘플은 `stg`·`prod` flavor로 시연 |
| 12 | 첨부 가능한 영상 형식·길이 | `video/*`, `image/*`만, 크기 상한만 검사. 길이 상한이 필요한가요? |
| 13 | 첫 사용 안내 문구와 비활성화 저장 위치 | 라이브러리 내부 SharedPreferences에 "안내 확인함" 저장 |
| 14 | Ink API 버전 | 펜·형광펜은 `androidx.ink` `1.1.0-alpha07`(android/cahier와 동일). Compose용 `InProgressStrokes`는 1.1 알파에만 있습니다. 배포 라이브러리가 알파에 의존해도 될까요? 또한 STG APK에 `libink.so`(ABI별)가 추가됩니다 |

## 아직 손대지 않은 범위

- 개발자 모드(호스트 저장소 키 토글): 호스트 앱의 실제 키·저장 방식이 확인되지 않아 Phase 1 핵심이 끝난 뒤 진행합니다. 첫 호스트 앱은 어떤 앱인가요?
- MediaProjection 녹화: 문서상 후속 범위.
