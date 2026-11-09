package ai.syne.app

import ai.syne.app.ui.DebugScreen
import ai.syne.app.ui.DebugViewModel
import ai.syne.app.ui.MainViewModel
import ai.syne.app.ui.Route
import ai.syne.app.ui.screens.AlbumScreen
import ai.syne.app.ui.screens.DrivingScreen
import ai.syne.app.ui.screens.HomeScreen
import ai.syne.app.ui.screens.LibraryScreen
import ai.syne.app.ui.screens.TrackScreen
import ai.syne.core.design.SyneDesign
import ai.syne.core.design.SyneDims
import ai.syne.core.design.SyneType
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val debugVm: DebugViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            SyneLog.i("permissions: $result")
            debugVm.startSignals() // 거부돼도 시작: 해당 채널은 FAILED → AUTO면 Replay로 전환
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyneDesign(SyneType.Car, SyneDims.Car) { SyneRoot(vm, debugVm) }
        }

        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty() || savedInstanceState != null) {
            debugVm.startSignals()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private companion object {
        /** 매니페스트 선언 중 런타임 승인이 필요할 수 있는 것들. 기기에 정의되지 않은 권한은 자동 거부된다. */
        val RUNTIME_PERMISSIONS = listOfNotNull(
            "android.car.permission.CAR_SPEED", // 거부 시 SPEED 채널 FAILED
            "android.car.permission.CAR_POWERTRAIN",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null, // 재생 알림
        )
    }
}

/**
 * 화면 전환 규칙
 * - 기어가 P가 아니면 무조건 주행 화면(고정·무터치). 예외: 개발자 메뉴(디버그)가 열려 있을 때.
 * - P에서는 홈(장르) → 보관함 → 앨범 → 트랙.
 */
@Composable
private fun SyneRoot(vm: MainViewModel, debugVm: DebugViewModel) {
    val driving by vm.driving.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()

    BackHandler(enabled = !driving && route != Route.Home) { vm.pop() }

    if (driving && route != Route.Debug) {
        DrivingScreen(vm)
        return
    }
    when (val r = route) {
        Route.Home -> HomeScreen(vm)
        Route.Library -> LibraryScreen(vm)
        is Route.Album -> AlbumScreen(vm, r.id)
        is Route.Track -> TrackScreen(vm, r.id, r.fresh)
        Route.Debug -> DebugScreen(debugVm, onClose = { vm.pop() }, onArtLab = { vm.push(Route.ArtLab) })
        Route.ArtLab -> ai.syne.app.ui.screens.ArtLabScreen(onBack = { vm.pop() })
        is Route.Edit -> ai.syne.app.ui.screens.AlbumEditScreen(vm, r.id)
    }
}
