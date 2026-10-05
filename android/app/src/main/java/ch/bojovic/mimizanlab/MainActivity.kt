package ch.bojovic.mimizanlab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.bojovic.mimizanlab.ui.CameraScreen
import ch.bojovic.mimizanlab.ui.CameraViewModel
import ch.bojovic.mimizanlab.ui.ReviewScreen
import ch.bojovic.mimizanlab.ui.ReviewViewModel
import ch.bojovic.mimizanlab.ui.RollScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MimizanTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    App()
                }
            }
        }
    }
}

@Composable
fun MimizanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE6E6E6),
            onPrimary = Color.Black,
            secondaryContainer = Color(0xFF3A3A3A),
            onSecondaryContainer = Color.White,
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color(0xFF151515),
            surfaceContainerLow = Color(0xFF101010),
            onSurface = Color(0xFFE6E6E6),
        ),
        content = content,
    )
}

private sealed interface Screen {
    data object Camera : Screen
    data object Roll : Screen
    data class Review(val name: String) : Screen
}

@Composable
private fun App() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.CAMERA] == true
        asked = true
    }
    val wanted = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.POST_NOTIFICATIONS)
            // Runtime permission since Android 17 for the orientation sensor.
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.OTHER_SENSORS")
        }.toTypedArray()
    }
    LaunchedEffect(Unit) {
        val missing = wanted.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) launcher.launch(wanted)
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mimizan Lab Mobile", style = MaterialTheme.typography.headlineMedium)
            Text("needs the camera to record RAW frames.", modifier = Modifier.padding(vertical = 16.dp))
            Button(onClick = { launcher.launch(wanted) }) {
                Text(if (asked) "Grant camera access" else "Continue")
            }
        }
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    val cameraVm: CameraViewModel = viewModel()
    val reviewVm: ReviewViewModel = viewModel()

    when (val s = screen) {
        Screen.Camera -> CameraScreen(
            vm = cameraVm,
            onOpenShot = { screen = Screen.Review(it) },
            onOpenRoll = { screen = Screen.Roll },
        )
        Screen.Roll -> {
            BackHandler { screen = Screen.Camera }
            RollScreen(onOpen = { screen = Screen.Review(it) }, onBack = { screen = Screen.Camera })
        }
        is Screen.Review -> {
            BackHandler { screen = Screen.Camera }
            ReviewScreen(vm = reviewVm, name = s.name, onBack = { screen = Screen.Camera })
        }
    }
}
