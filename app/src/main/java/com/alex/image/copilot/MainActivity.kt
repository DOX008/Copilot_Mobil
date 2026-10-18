package com.alex.image.copilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import com.alex.image.copilot.databinding.ActivityMainBinding
import com.alex.image.copilot.ui.home.HomeFragment
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_medvind,
                R.id.nav_orncom,
                R.id.nav_anotepad,
                R.id.nav_googles,
                R.id.nav_duckduckgo,
                R.id.nav_cardscanner,
                R.id.nav_home,
                R.id.nav_gemini,
                R.id.nav_chatgpt,
                R.id.nav_extensions
            ),
            drawerLayout
        )

        navView.setupWithNavController(navController)

        onBackPressedDispatcher.addCallback(this) {
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
            val current =
                navHost?.childFragmentManager?.primaryNavigationFragment

            val handled = (current as? HomeFragment)?.onBackPressed() ?: false
            if (!handled) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // ---- NYTT FÖR MIKROFON ----
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                123
            )
        }
        // ---- SLUT NYTT ----
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}