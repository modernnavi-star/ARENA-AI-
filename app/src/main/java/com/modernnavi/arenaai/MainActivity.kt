package com.modernnavi.arenaai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modernnavi.arenaai.data.ArenaViewModel
import com.modernnavi.arenaai.theme.ArenaTheme
import com.modernnavi.arenaai.ui.ArenaAiApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArenaTheme {
                val viewModel: ArenaViewModel = viewModel()
                ArenaAiApp(viewModel = viewModel)
            }
        }
    }
}
