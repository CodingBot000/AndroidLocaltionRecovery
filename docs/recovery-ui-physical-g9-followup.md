# PR #9 G9 미완료 항목 재시험

## 시작 상태

2026-11-27 KST에 기존 [G9 관측](recovery-ui-physical-g9.md)의 미완료 범위를 이어서 확인했다. 기준 A `a732af2dddfb86514fbfa76a6d7971f31e8d23da`, 후보 C `ce617de21ffbf5485564c9d7bc589a3b6085c02c`, 시작 PR head `1ff8bf8e67243e9157b58dd8a0eb970a3766b54b`를 사용했다. [이전 익명화 관측](benchmarks/sm-g975n-pr9-g9.json)은 그대로 보존했다.

사용자가 단말을 잠금 해제한 뒤 ADB에서 잠금 화면이 보이지 않고 런처가 전면에 있음을 확인했다. 이로써 이전 시험의 홈 화면 복원을 확인했다. 시작 시 `dumpsys window policy`의 `secure` 표시는 false였지만 본 시험의 Wake 직후에는 true였다. 이 값만으로 보안 잠금 설정이 꺼졌다고 판단할 수 없었다. 보안 설정은 시험에서 변경하지 않았다.

## A 전체 수명 주기 재시험

이전 시험과 같은 제품 APK, 동일 수명 계측 APK, 같은 정밀·대략 위치 임시 권한 범위와 `retainsPhysicalSessionAcrossRecreationHomeAndScreenOff` 메서드를 사용했다. Activity 재생성 3회와 Home 전환 뒤 화면 OFF **303.560초** 동안 같은 세션 토큰·FGS·현재 GPS 리스너의 유지를 확인했다. 이 구간의 콜백은 0건이었다.

Wake 뒤 보안 잠금이 표시돼 사용자에게 직접 해제를 요청했다. 원래 **90초** 동안 해제되지 않아 전면 복귀와 UI Stop의 단언에서 시간 초과했다. 실패 정리 후 FGS·현재 GPS 리스너는 각각 0개였고 두 임시 위치 권한을 회수했다. 이전 시도의 303.979초와 이번 303.560초는 별도의 관측으로 남겼다.

## 남은 범위와 판정

이후에도 단말이 잠겨 C의 수명 계측은 시작 조건에서 **BLOCKED**였다. A/C의 추가 fresh GPS·직접 provider 대조도 같은 잠금으로 시작할 수 없었다. 이번 재시험에는 새 GPS 수신 표본이 없다. 이전 A/C Live와 직접 provider의 무수신·관측 위성 0 결과는 [최초 관측](recovery-ui-physical-g9.md#수신과-직접-provider-대조)에 보존했다.

**G9는 INCONCLUSIVE다.** A의 재생성 및 화면 OFF 동안의 유지 증거를 한 차례 추가했다. A의 잠금 복귀·UI Stop과 C의 전체 수명, 추가 GPS 대조는 BLOCKED다. G3·G7·G8은 INCONCLUSIVE를 유지했다. 제품 코드와 GPS·화면 OFF·잠금 해제 제한은 그대로 사용했다.

## 복원과 증거

APK 6개의 SHA-256, 기존 계측 미디어 13개, 앱 데이터 디렉터리와 패키지 중지 상태, Live 중단 상태 파일, 정밀·대략 위치 권한과 플래그를 대조했다. 화면 크기·density·추적한 화면·네트워크 설정과 위치 스위치도 이번 시험 전과 일치했다. A가 생성한 단말 임시 파일은 로컬 원본과 해시를 비교한 뒤 제거했다. 시험이 끝난 시점에는 화면을 깨운 뒤 보안 잠금 화면이 남아 있어 이번 시험 전의 런처 전면 상태로 돌아가려면 사용자 잠금 해제가 필요했다.

원본 AndroidJUnitRunner 출력·태그 로그·APK와 복원 기록은 Git에서 제외한 `build/reports/recovery-ui/pr9-g9-physical-20261127/followup/`에 보존했다. [추가 익명화 관측](benchmarks/sm-g975n-pr9-g9-followup.json)
