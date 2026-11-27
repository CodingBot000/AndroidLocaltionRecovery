# Location Recovery Lab

GPS가 튀거나 끊겼을 때 Android 앱이 위치를 언제 보정하고, 어디까지 추정하고, 언제 추정을 멈춰야 하는지 검증한 개인 프로젝트다. Kotlin·Jetpack Compose로 구현했으며 외부 지도 SDK 없이 국내 OpenStreetMap 10개 지역의 도로망을 오프라인으로 표시한다.

[OL07 시연](docs/demo.md#ol07-시연) · [상세 아키텍처](docs/architecture.md) · [통합 검증](docs/verification.md) · [최종 판정과 잔여 위험](docs/recovery-ui-final-acceptance.md)

## 대표 시연 · 남산 터널 OL07

<p align="center">
  <a href="docs/demo/recovery-ui/ol07-tracking.png"><img src="docs/demo/recovery-ui/ol07-tracking.png" width="220" alt="OL07 28초 정상 추적 및 도로 매칭 위치"></a>
  <a href="docs/demo/recovery-ui/ol07-estimating.png"><img src="docs/demo/recovery-ui/ol07-estimating.png" width="220" alt="OL07 54초 GPS 신호 손실 및 DR 추정"></a>
  <a href="docs/demo/recovery-ui/ol07-recovered.png"><img src="docs/demo/recovery-ui/ol07-recovered.png" width="220" alt="OL07 73초 SIGNAL_RECOVERED 및 정상 추적 복귀"></a>
</p>
<p align="center">28초 정상 추적 &nbsp; | &nbsp; 54초 GPS 손실·DR 추정 &nbsp; | &nbsp; 73초 정상 복구</p>

최종 제품 `ce617de`의 실제 앱 실행 화면이다. 도로 형상은 실제 OSM 데이터이며 GPS 관측과 차량 움직임은 가상이다. **대표 사례(Demos) → OL07 → 5× → 재생(Play)**을 선택하면 63초에 추정 한도에 도달해 마지막 확인 위치로 전환하고, 73초에 정상 추적으로 복귀하는 흐름을 볼 수 있다. [63초 추정 중단 화면](docs/demo/recovery-ui/ol07-last-known.png) · [시연 절차와 판정 로그](docs/demo.md#ol07-시연)

## 핵심 기술 성과

| 기술 과제 | 구현과 검증 |
|---|---|
| **위치 엔진** | 시각 오류와 위치 점프를 차단하고 Spatial Grid로 후보 도로를 좁힌다. 신호가 끊기면 최대 15초 Dead Reckoning을 적용하고, 추정 상한에 도달하면 마지막 확인 위치를 표시한다. 연속 유효 관측으로 복구를 확인한다. [위치 알고리즘](docs/algorithm.md) |
| **저메모리 OOM 대응** | API 26·48MiB에서 지도 로딩 OOM과 Live 반복 중 OS 종료를 재현했다. 항목별 JSON 파싱으로 로딩 할당을 줄이고, 해당 환경의 정적 지도는 Software Canvas와 재사용 비트맵으로 그린다. 최종 제품에서도 O10 기동과 지도 전환·재생 회귀를 확인했다. [장애 재현과 수정](docs/stability-investigation.md#핵심-사례-저메모리-지도-oom) |
| **성능 계측과 원인 분석** | Macrobenchmark·Perfetto로 지도 준비, 첫 화면 표시, UIAutomator 관측을 같은 요청에 연결했다. 이전 UI 후보의 큰 전환 지연이 주로 화면 표시 뒤 접근성 노드를 기다리는 구간에 집중됨을 확인했다. 접근성 캐시를 비우는 대조 실험으로 관측 지연을 분리했다. [지도 전환 RCA](docs/recovery-ui-switch-rca.md) · [최종 주행](docs/recovery-ui-emulator-g3-final-ride.md) |
| **Android 생명주기** | 위치 Foreground Service가 세션을 소유하고 Activity는 해당 세션을 관찰한다. Stop·권한 철회 시 리스너와 세션 자원을 해제한다. 최종 제품의 실기기 Live 시작·종료 20회에서 해제를 확인했다. [Live 구조와 권한](docs/live-location.md) · [실기기 자원 해제](docs/recovery-ui-physical-g8.md#지도-전환재생과-live-해제) |
| **자동화 테스트** | 최종 제품의 JVM·Android 단위 테스트 83개가 통과했다. 독립 위치 평가의 기존 결과 JSON 5종이 바이트 단위로 일치했고, 실제 OL07 UI의 1×·5× 결과는 독립 재생 세션과 일치했다. GitHub Actions에서 모듈 경계·빌드·Lint·fixture 검사와 API 36 Live 계측을 실행한다. [통합 검증](docs/verification.md) · [PR 검사](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9/checks) |

## 아키텍처

순수 Kotlin 위치·지도·시뮬레이션 코어를 Compose UI와 Android 위치 API에서 분리했다. 기본 앱은 위치·인터넷 권한 없이 가상 주행을 재생하고, 별도 Live APK는 Android 위치 수신과 서비스 수명을 다룬다. HTTP 지연·취소·응답 역전 실험은 독립 JVM 모듈에서 실행한다. [9개 모듈과 데이터 흐름](docs/architecture.md) · [HTTP 장애 실험](docs/network-reliability.md)

## 검증 범위와 남은 위험

- **최종 제품 `ce617de`** · 에뮬레이터 성능 **G3 PASS**. 실기기 성능·메모리·GPS **G7·G8·G9 INCONCLUSIVE**. Native Heap PSS의 더 큰 증가 기울기가 두 내구 비교에서 반복됐다. A/C 모두 Live와 직접 GPS 요청에서 새 fix가 없어 실제 fix 기반 화면을 검증하지 못했고, A의 잠금 이후 복귀·UI Stop과 C의 화면 OFF·복귀 계측도 미완료다. [최종 수용 판단과 잔여 위험](docs/recovery-ui-final-acceptance.md)
- **과거 빌드** · 실제 GPS 수신은 `d0b3822`의 실내 시험에서 확인했다. Spatial Grid JVM 측정과 렌더링 후보 기각 또한 각 기준 빌드의 검증 이력이다. [과거 실기기 범위](docs/physical-device-validation.md#기존-확인-범위-2026-11-2526) · [위치 매칭 측정](docs/refactor-performance.md#jvm-도로-매칭) · [렌더링 후보 기각](docs/rendering-memory-rca.md)

프로젝트 소유자의 잔여 위험 수용과 병합 승인은 미완료이며 [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9)는 Draft 상태다.

## Quick Start

JDK 17, Android SDK 36, API 26 이상 기기 또는 에뮬레이터에서 기본 앱을 실행한다.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n lab.recovery.app/.MainActivity
```

[Live APK 실행](docs/live-location.md#실행-방법) · [테스트 및 재현 명령](docs/verification.md) · [전체 기술 문서](docs/index.md)

© OpenStreetMap contributors · 지도 파생 데이터 ODbL 1.0. [데이터 출처와 라이선스](data/osm/README.md)
