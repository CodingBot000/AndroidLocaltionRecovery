# 기술 문서 안내

[프로젝트 README](../README.md) · [실행 가이드](demo.md)

설계·구현·검증 근거를 찾기 위한 색인이다. 현재 구현과 실험 후보, 과거 측정 이력을 구분한다.

## 먼저 읽을 문서

| 관심 영역 | 문서 | 확인할 내용 |
|---|---|---|
| Android 아키텍처 | [시스템 아키텍처](architecture.md) | Gradle 9개 모듈, MVVM·순수 Kotlin 코어, 취소·실행 세대, 의존성 검사 |
| 위치 기반 서비스 | [위치 엔진](algorithm.md) · [Android Live](live-location.md) | Map Matching·DR·재획득, 위치 권한·FGS, 세션·리스너 해제 |
| 앱 성능 | [지도 렌더링 설계](korea-map-design.md) · [성능 리팩터링](refactor-performance.md) · [실기기 렌더링·메모리 RCA](rendering-memory-rca.md) | Spatial Grid·LOD·Culling·캐시, Macrobenchmark·Perfetto, 후보 기각 |
| 안정성 | [장애 분석](stability-investigation.md) · [Compose 계측 RCA](compose-slot-table-rca.md) | API 26 저메모리 OOM/OS 종료, Debug ANR·Crash 진단, CI 실패 분석 |
| 네트워크 | [HTTP 신뢰성](network-reliability.md) | 독립 JVM loopback 서버, timeout·retry·취소·응답 정합성 |
| 품질 검증 | [통합 검증](verification.md) · [실기기 검증 범위](physical-device-validation.md) · [2026-11-25 실기기 검증](real-device-final-validation.md) | 회귀 테스트·CI, Android 버전별 계측, 실기기 GPS·내구·성능 |

## 데이터와 실행 화면

| 문서 | 내용 |
|---|---|
| [OSM 파이프라인](osm-data-pipeline.md) | PBF → 방향성 도로망·좌표계·표현 데이터, 체크섬 및 독립 평가 데이터 |
| [OSM 화면 검수](osm-visual-verification.md) | 국내 10개 지역과 Follow·Mid·Overview 화면 |
| [데모](demo.md) | 앱에서 장애·복구·지도 제어를 확인하는 방법 |
| [위치 복구 UI 검증](recovery-ui-validation.md) | 실제 OL07 화면, 접근성·OS 회귀 및 에뮬레이터 성능 비교 |
| [지도 전환 성능 조사](recovery-ui-switch-rca.md) | 앱 내부 준비, 실제 첫 프레임, 접근성 관측 및 최종 비교 |
| [데이터 출처·라이선스](../data/osm/README.md) | OpenStreetMap/ODbL 표기 및 출처 |

## 정량 평가

아래 표는 가상 관측과 JVM 실행 결과를 보존한다. 실제 도로 형상을 사용해도 가상 입력의 정확도를 실제 GNSS 정확도로 해석하지 않는다.

| 문서 | 범위 |
|---|---|
| [기본 합성 회귀](evaluation-report.md) | v1 빠른 회귀 |
| [장시간 합성 평가](extended-evaluation-report.md) | L / DEMO_LONG / E |
| [OSM 평가](osm-evaluation-report.md) | 실제 도로망 위 가상 주행, 별도 OE 평가 |
| [OSM 고난도 평가](osm-challenge-report.md) | 별도 시드의 CE 8개, 정확도와 보류·복구 지표 |

## 분석 참고 자료 및 과거 이력

| 문서 | 범위 |
|---|---|
| [렌더링·메모리 분석 지표](rendering-memory-interview-notes.md) | TTID·TTFD·FrameTimeline·PSS·heap 분석 및 실험 설계 |
| [초기 OSM 성능](korea-map-performance.md) | 에뮬레이터 단회 비교; 최신 실기기 수치와 별개 |
| [합성 지도·시나리오](map-and-scenarios.md) | OSM 이전 데이터·주행 계약 |
| [합성 지도 디자인](map-design.md) · [시각 검수](map-visual-verification.md) | OSM 이전 표현 개정과 검수 결과 |
| [초기 구현 계획 이력](implementation-plan.md) | 단계별 구현·검증 기준의 역사적 기록 |

실험별 기준 SHA와 빌드·환경·측정 조건을 연결한 문서를 근거로 사용한다. 과거 실패나 미완료 판정은 해당 실험의 이력이며, 현재 확인 범위는 [통합 검증의 최신 요약](verification.md#최신-구현-검증-요약)에서 확인한다.
