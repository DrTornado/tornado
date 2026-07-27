package com.tornado.vocab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.tornado.vocab.audio.PlayerController
import com.tornado.vocab.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerController.connect(this)
        setContent {
            TornadoTheme {
                val nav = rememberNavController()
                val vm: WordViewModel = viewModel()
                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        WordListScreen(vm) { id -> nav.navigate("detail/$id") }
                    }
                    composable(
                        "detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType })
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        WordDetailScreen(vm, id) { nav.popBackStack() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        PlayerController.release()
        super.onDestroy()
    }
}
