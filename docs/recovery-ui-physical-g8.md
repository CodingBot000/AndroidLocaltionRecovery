# PR #9 실기기 메모리·내구 검증

## 범위와 비교 조건

2026-11-27 KST에 SM-G975N(Android 12/API 31, Exynos 9820, ARM64)에서 기준 A `a732af2dddfb86514fbfa76a6d7971f31e8d23da`와 제품 C `ce617de21ffbf5485564c9d7bc589a3b6085c02c`를 비교했다. 시작 시 PR #9 head는 `ba486001078864e6edb9e3909885e0f7113085a8`였으며 C와 제품 소스 및 빌드 설정이 일치했다. 서로 다른 격리 worktree에서 앱·계측 및 Live·계측 Debug APK를 빌드했다. `PhysicalOsmEnduranceTest`, `PlatformReplayStressTest`, `PhysicalLocationDiagnosticsTest`의 소스는 두 비교점에서 일치했다. [G7 실기기 성능](recovery-ui-physical-g7.md)

단말은 USB 충전 상태였으며 화면 오버라이드는 1,080×2,280, density는 420, 글꼴 배율은 1.1, 시스템 언어는 `ko-KR,en-US`였다. 내구와 오프라인 전환 시험은 동의한 비행기 모드 ON·Wi-Fi OFF에서 실행했다. 각각의 오프라인 시험이 끝난 뒤 비행기 모드 OFF·Wi-Fi ON을 복원했다. Live 반복 시험은 원래 위치 스위치를 유지하고 동의한 정밀·대략 위치 권한만 임시 부여했다. 선택한 Live 메서드는 O01 10회와 O10 10회의 서비스 시작·종료와 해제를 검증한다. G9 실제 GPS 수신 시험은 **NOT_RUN**이다.

기존 설치 APK 6개와 측정 미디어 파일 13개를 실제 파일로 백업하고 SHA-256을 남겼다. 테스트 APK는 동일한 서명을 확인하고 기존 앱 데이터 디렉터리를 유지하는 `install -r`로 교체했다. 단말 설정, 홈 화면, Live 위치 권한과 중단 상태 파일도 기록했다.

## 측정 정의

각 빌드의 O10 1× 180초×10회와 마지막 Reset 후 유휴 120초까지는 **빌드당 한 번의 독립 내구 실행**이다. 기존 계측의 동일 PID, 완료 이벤트, 가상 180초, 실제 1× 경과, 로그·출처별 주행 이력 상한, Reset 뒤 새 실행 ID와 로그·이력·평가 초기화를 유지했다. `ready`, `playing`, `completed`, `reset`, `idle60s`, `idle120s` 표본을 원래 정의로 수집했다. 전환·Activity 재생성과 Live 반복은 내구 실행을 끝낸 뒤 별도 계측했다. 내구 측정 중 앱을 재시작하거나 캐시를 비우거나 GC를 요청하지 않았다.

전체·Dalvik·Native Heap PSS와 Graphics는 `Debug.MemoryInfo`에서, Java 사용 힙은 `Runtime.totalMemory() - freeMemory()`에서 가져왔다. Native allocation은 별도 항목이며 Native Heap PSS가 존재하는데 API가 0인 표본은 측정 불가로 기록한다. 완료 중앙값과 Theil–Sen은 2–10회 완료 표본으로 계산한다. A 대비 C의 전체 및 Native Heap PSS의 관측 최대값·완료 중앙값·Reset 후 유휴 120초 중 하나라도 10% 이상 증가하면 조사한다. 회차별 상승 재현·정리 실패·OOM/OS 종료도 조사 조건이다.

## 내구 결과

두 빌드 모두 10회 주행과 Reset, 같은 프로세스의 유휴 120초까지 통과했다. 실제 1× 주행 시간 합계는 A **1,801.018초**, C **1,801.409초**였다. A 62개/C 63개 메모리 표본을 보존했다. 표본상 최대 backlog는 두 빌드 모두 3ms, 최대 로그 223개, 전체 주행 이력 168점이었다. 완료·Reset 및 출처별 주행 이력 상한의 기존 단언이 모두 통과했다. A의 단말 온도는 30.6–34.0°C, C는 29.5–31.5°C였고 thermal status는 양쪽 모두 0이었다. 두 시험 모두 USB가 연결돼 있었으며 샘플에서 관측한 배터리 잔량은 A 81–98%, C 100%였다.

| 같은 관측 시점 | A 전체 / Native PSS (KiB) | C 전체 / Native PSS (KiB) | A Java 사용 힙 / Graphics (KiB) | C Java 사용 힙 / Graphics (KiB) |
|---|---:|---:|---:|---:|
| 1회 준비 | 226,176 / 31,816 | 209,838 / 31,135 | 47,911 / 45,747 | 45,188 / 33,691 |
| 1회 완료 | 396,527 / 89,734 | 383,163 / 88,669 | 60,302 / 143,951 | 48,213 / 143,691 |
| 10회 완료 | 419,804 / 109,739 | 432,948 / 112,939 | 54,161 / 152,083 | 62,544 / 153,431 |
| 10회 Reset | 422,780 / 109,955 | 419,260 / 116,563 | 57,193 / 152,083 | 43,654 / 154,967 |
| Reset 후 60초 | 394,077 / 113,309 | 380,550 / 114,475 | 57,209 / 118,831 | 43,686 / 117,863 |
| Reset 후 120초 | 384,256 / 103,730 | 380,640 / 114,467 | 57,225 / 118,831 | 43,686 / 117,863 |

| 분포 또는 기울기 | A | C | C−A 상대 변화 |
|---|---:|---:|---:|
| 최대 관측 전체 PSS (KiB) | 426,749 | 437,070 | +2.42% |
| 최대 관측 Native PSS (KiB) | 113,309 | 117,227 | +3.46% |
| 2–10회 완료 전체 PSS 중앙값 (KiB) | 413,606 | 415,612 | +0.49% |
| 2–10회 완료 Native PSS 중앙값 (KiB) | 103,022 | 105,804 | +2.70% |
| 2–10회 완료 Java 사용 힙 중앙값 (KiB) | 52,083 | 51,982 | −0.19% |
| 2–10회 완료 Graphics 중앙값 (KiB) | 152,083 | 152,651 | +0.37% |
| 전체 PSS Theil–Sen (KiB/회) | 2,662.25 | 4,537.53 | +70.44% |
| Native PSS Theil–Sen (KiB/회) | 1,354.53 | 2,609.00 | +92.61% |
| Java 사용 힙 Theil–Sen (KiB/회) | 796.13 | 1,715.41 | +115.47% |
| Graphics Theil–Sen (KiB/회) | 228.00 | 182.40 | −20.00% |
| 유휴 120초 전체 PSS (KiB) | 384,256 | 380,640 | −0.94% |
| 유휴 120초 Native PSS (KiB) | 103,730 | 114,467 | **+10.35%** |

### Native Heap 조사와 판정

10% 조사 기준을 넘은 항목은 유휴 120초 Native Heap PSS였다. 유휴 60초에는 C가 A보다 1,166KiB(1.03%) 높았다. 이후 A의 Native PSS는 9,579KiB 줄었고 C는 8KiB 줄었다. 완료 표본의 전체·Native PSS 기울기는 C가 더 컸다. 동시에 마지막 유휴 전체 PSS는 C가 3,616KiB 낮았고 Java 사용 힙과 Graphics 역시 더 낮았다. 두 빌드 모두 후반 Native allocation API가 0을 보고한 표본은 측정 불가여서, 이 로그만으로 살아 있는 할당과 재사용을 위한 메모리 보유를 분리할 수 없다.

**G8 성능 판정은 INCONCLUSIVE다.** 기능 내구는 두 빌드에서 통과했다. 유휴 Native PSS 기준 초과와 커진 완료 구간 기울기가 각각 한 번의 독립 시험에서 관찰돼 재현성이나 누수 원인을 확정하지 못했다. 실행 순서의 온도·충전 구간 차이를 기록했다. 본 시험의 수치를 바꿀 별도 heap dump·GC·재시작은 수행하지 않았다.

## 지도 전환·재생과 Live 해제

A와 C 모두 O01/O10 전환·재생 20회 및 네 차례 Activity 재생성을 통과했다. 같은 실행 ID가 재생성 뒤 유지됐으며 새 시나리오와 최종 Reset에서 이전 로그·이력·평가 초기화를 확인했다. 전환 시험의 마지막 두 표본 전체 PSS는 O01 A 304,731/C 308,485KiB 및 O10 A 267,463/C 263,177KiB였다.

Live는 A와 C 각각 O01 10회·O10 10회 시작·종료를 통과했다. 매 시작에서 서비스 승격, ACTIVE와 현재 리스너 등록, 증가한 세션 토큰을 검사했다. 매 Stop은 IDLE·FGS 해제·리스너 해제와 읽기 상태 초기화를 확인했다. 마지막 Stop 뒤 별도 조회에서도 두 앱의 서비스와 활성 GPS 리스너가 각 0개였다. 아래 Stop 시간은 UI 탐색·터치와 상태 폴링을 포함한다.

| Live 관측값 | A | C |
|---|---:|---:|
| O01 Stop 중앙값 (ms) | 966.5 | 987.5 |
| O10 Stop 중앙값 (ms) | 914.5 | 915.5 |
| O01 10회 Stop 전체 / Native PSS (KiB) | 203,081 / 44,015 | 198,973 / 41,300 |
| O10 20회 Stop 전체 / Native PSS (KiB) | 282,392 / 87,671 | 293,064 / 86,531 |

## 복원과 자료

시험 뒤 설치 APK 6개, 기존 계측 미디어 13개의 SHA-256을 시험 전과 대조했다. 앱 데이터 디렉터리 inode와 패키지 중지 상태, Live 중단 상태 파일, 두 위치 권한 및 플래그, 위치 스위치, 홈 화면과 추적한 화면·네트워크 설정이 일치했다. 패키지 중지 상태가 달라진 오프라인 앱은 원래 APK로 한 번 열고 홈으로 돌아온 뒤 백그라운드 프로세스를 종료해 이전 상태로 복원했다. G8이 생성한 단말 임시 파일은 로컬 복사본의 해시를 확인한 뒤 제거했다. G7 **INCONCLUSIVE** 판정을 유지했다.

원본 AndroidJUnitRunner 출력, 동일 프로세스 메모리 로그, 전환·Live 표본, 환경·APK·복원 기록은 로컬 Git 제외 경로 `build/reports/recovery-ui/pr9-g8-physical-20261127/`에 보존했다. [익명화 반복별 데이터와 APK 해시](benchmarks/sm-g975n-pr9-g8.json)

### 재현 명령

정확한 A/C SHA에서 `:app:assembleDebug :app:assembleDebugAndroidTest :live:assembleDebug :live:assembleDebugAndroidTest`를 빌드한다. 설치 APK·계측 미디어·단말 설정과 권한을 백업하고 각 빌드의 APK를 `install -r`로 교체한다. 비행기 모드 ON·Wi-Fi OFF에서 내구·전환을 실행하고 원래 네트워크를 복원한 뒤, 승인된 위치 권한으로 Live 해제만 실행한다.

```bash
adb -d shell am instrument -w -r -e physicalEndurance true \
  -e class lab.recovery.app.PhysicalOsmEnduranceTest \
  lab.recovery.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r \
  -e class lab.recovery.app.PlatformReplayStressTest \
  lab.recovery.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e realLocation true \
  -e class 'lab.recovery.live.PhysicalLocationDiagnosticsTest#releasesTwentyPhysicalSessions' \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner
python3 -B tools/summarize_render_endurance.py "$PRIVATE_ENDURANCE_LOG" --output endurance.json
```

내구 로그는 시작 직전부터 `adb -d logcat -v epoch -T 1 -s PhysicalOsmEndurance:I`로 수집한다. Live 계측의 `realLocation`은 물리 단말의 서비스 수명 메서드를 실행하기 위한 기존 가드다.
