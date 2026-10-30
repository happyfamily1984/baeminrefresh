package com.baeminrefresh.app

/**
 * 기능 플래그.
 *
 * PEE: 소변 타이머(개인용/임시 기능). 공개 배포할 때는 이 값을 false 로 바꾸면
 * 설정의 토글과 플로팅 버튼·알림이 모두 사라진다. (완전히 들어내려면 PEE 관련 코드와
 * ic_water_splash, Prefs 의 KEY_PEE_* 를 지우면 된다.)
 */
object Features {
    const val PEE = true
}
