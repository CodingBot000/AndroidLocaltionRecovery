package lab.recovery.app

import lab.recovery.feature.LabViewModel
import lab.recovery.feature.RecoveryLab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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
        val legacyBenchmark = packageName == "lab.recovery.app.benchmark" &&
            intent.getStringExtra("benchmarkScenario") == "DEMO"
        val factory = viewModelFactory {
            initializer {
                LabViewModel(ScenarioRepository { appAssets.open(it) }, legacyBenchmark)
            }
        }
        val model = ViewModelProvider(this, factory)[LabViewModel::class.java]
        setContent { RecoveryLab(model) }
    }
}
