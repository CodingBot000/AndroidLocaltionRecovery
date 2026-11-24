# OpenStreetMap 파생 데이터

© OpenStreetMap contributors. `fixtures/osm/`의 지역 추출 그래프, 지형과 OSM 좌표·에지 ID를 인용한 가상 주행 데이터는 ODbL 1.0으로 제공한다. 이 경로의 가공 JSON 전체가 배포용 파생 데이터베이스다. Android APK에도 동일한 JSON과 출처 안내를 포함한다. 기존 앱 소스 및 독립 합성 fixture의 라이선스 범위는 유지한다. [OpenStreetMap 저작권·출처](https://www.openstreetmap.org/copyright) · [ODbL 1.0 전문](https://opendatacommons.org/licenses/odbl/1-0/) · [표기 가이드](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines)

원본은 Geofabrik의 대한민국 날짜 고정 추출본이다. 원본과 지역별 SHA-256, 투영과 변환 버전, 제외 통계는 manifest에서 확인할 수 있다. 배포 데이터에는 원본이 제공하는 도로·지형의 위치와 도로 그래프에 필요한 OSM 객체 ID가 포함된다. 공공 시설명과 시설 종류는 지도 가독성에 사용한다. 주유소·주차장의 운영자 또는 상호, 주소, 연락처와 편집자 정보는 변환 결과에서 제외한다. [원본 PBF](https://download.geofabrik.de/asia/south-korea/south-korea-261101.osm.pbf) · [출처 manifest](../../fixtures/osm/manifest.json)

[데이터 변환과 오프라인 재생 방법](../../docs/osm-data-pipeline.md)
