package com.ingeint.checkin.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.ui.diagnostics.DiagnosticsScreen
import com.ingeint.checkin.ui.permissions.PermissionsWizardScreen
import com.ingeint.checkin.ui.placeholder.ChildHomeScreen
import com.ingeint.checkin.ui.placeholder.ParentHomeScreen
import com.ingeint.checkin.ui.setup.SetupScreen
import com.ingeint.checkin.ui.setup.SetupViewModel
import com.ingeint.checkin.ui.theme.CheckinTheme
import kotlinx.coroutines.flow.first

private object Routes {
    const val LOADING = "loading"
    const val SETUP = "setup"
    const val PERMISSIONS = "permissions/{role}/{childName}"
    const val CHILD_HOME = "child_home"
    const val PARENT_HOME = "parent_home/{childName}"
    const val DIAGNOSTICS = "diagnostics/{role}"

    fun permissions(role: String, childName: String) = "permissions/$role/$childName"

    fun parentHome(childName: String) = "parent_home/$childName"

    fun diagnostics(role: String) = "diagnostics/$role"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CheckinApp).container

        setContent {
            CheckinTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    CheckinNavHost(navController, container)
                }
            }
        }
    }
}

@Composable
private fun CheckinNavHost(navController: NavHostController, container: com.ingeint.checkin.AppContainer) {
    NavHost(navController = navController, startDestination = Routes.LOADING) {
        composable(Routes.LOADING) {
            LaunchedEffect(Unit) {
                val linked = container.prefs.isLinked()
                if (linked) {
                    val role = container.prefs.role.first()
                    val childName = container.prefs.childName.first() ?: ""
                    navController.navigate(
                        if (role == "child") Routes.CHILD_HOME else Routes.parentHome(childName),
                    ) { popUpTo(Routes.LOADING) { inclusive = true } }
                } else {
                    navController.navigate(Routes.SETUP) { popUpTo(Routes.LOADING) { inclusive = true } }
                }
            }
        }

        composable(Routes.SETUP) {
            val viewModel: SetupViewModel = viewModel(factory = AppViewModelFactory(container) { SetupViewModel(it) })
            SetupScreen(viewModel) { role, childName ->
                navController.navigate(Routes.permissions(role, childName)) {
                    popUpTo(Routes.SETUP) { inclusive = true }
                }
            }
        }

        composable(Routes.PERMISSIONS) { backStackEntry ->
            val role = backStackEntry.arguments?.getString("role") ?: return@composable
            val childName = backStackEntry.arguments?.getString("childName") ?: ""
            PermissionsWizardScreen(role = role) {
                val destination = if (role == "child") Routes.CHILD_HOME else Routes.parentHome(childName)
                navController.navigate(destination) {
                    popUpTo(Routes.PERMISSIONS) { inclusive = true }
                }
            }
        }

        composable(Routes.CHILD_HOME) {
            ChildHomeScreen(onOpenDiagnostics = { navController.navigate(Routes.diagnostics("child")) })
        }

        composable(Routes.PARENT_HOME) { backStackEntry ->
            val childName = backStackEntry.arguments?.getString("childName") ?: ""
            ParentHomeScreen(
                childName = childName,
                onOpenDiagnostics = { navController.navigate(Routes.diagnostics("parent")) },
            )
        }

        composable(Routes.DIAGNOSTICS) { backStackEntry ->
            val role = backStackEntry.arguments?.getString("role") ?: "parent"
            DiagnosticsScreen(role = role)
        }
    }
}
