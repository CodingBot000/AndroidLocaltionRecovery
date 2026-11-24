package lab.recovery.app

import lab.recovery.feature.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import lab.recovery.simulation.FixtureReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val appAssets = applicationContext.assets
        val factory = viewModelFactory {
            initializer {
                LabViewModel(load = { choice -> FixtureReader.read(appAssets.open(choice.path)) })
            }
        }
        val model = ViewModelProvider(this, factory)[LabViewModel::class.java]
        setContent { RecoveryLab(model) }
    }
}
