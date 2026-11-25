package lab.recovery.app

import lab.recovery.feature.LabViewModel
import lab.recovery.feature.RecoveryLab
import lab.recovery.feature.UiIntent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import lab.recovery.simulation.ScenarioRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val appAssets = applicationContext.assets
        val benchmarkScenario = intent.getStringExtra("benchmarkScenario")
            .takeIf { packageName == "lab.recovery.app.benchmark" }
        val factory = viewModelFactory {
            initializer {
                LabViewModel(ScenarioRepository { appAssets.open(it) }, benchmarkScenario)
            }
        }
        val model = ViewModelProvider(this, factory)[LabViewModel::class.java]
        val optimizedMap = packageName != "lab.recovery.app.benchmark" ||
            !intent.getBooleanExtra("benchmarkRawMap", false)
        setContent {
            val screen = model.state.collectAsState()
            val readyRun = remember { mutableStateOf<Long?>(null) }
            ReportDrawnWhen { screen.value.scenario != null && readyRun.value == screen.value.runId }
            RecoveryLab(model, optimizedMap) { readyRun.value = it }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (packageName != "lab.recovery.app.benchmark") return
        val scenario = intent.getStringExtra("benchmarkSelect") ?: return
        val model = ViewModelProvider(this)[LabViewModel::class.java]
        model.state.value.choices.firstOrNull { it.id == scenario }?.let {
            model.send(UiIntent.Select(it))
        }
    }
}
