package com.example.androidbackupgui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.androidbackupgui.databinding.ActivityMainBinding
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.backup.LogUtil
import com.example.androidbackupgui.ui.BackupFragment
import com.example.androidbackupgui.ui.ConfigFragment
import com.example.androidbackupgui.ui.RestoreFragment
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pageTitles = listOf("应用备份", "应用恢复", "备份配置")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DynamicColors.applyToActivitiesIfAvailable(application)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure libsu with global mount namespace support
        RootShell.configure()

        // Request root access on startup
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                RootShell.ensureSession()
            }
            // Initialize file-based logging
            LogUtil.init(filesDir)
        }

        // Edge-to-edge: distribute system bar insets (status bar, nav bar, cutout) to children
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Pad toolbar below status bar (preserve existing horizontal padding)
            binding.topAppBar.setPadding(
                binding.topAppBar.paddingLeft,
                statusBars.top,
                binding.topAppBar.paddingRight,
                binding.topAppBar.paddingBottom
            )

            // Pad bottom nav above navigation bar so menu items are visible
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft,
                binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight,
                navBars.bottom
            )

            // Pad view pager above navigation bar so fragment content doesn't overlap nav bar
            binding.viewPager.setPadding(
                binding.viewPager.paddingLeft,
                binding.viewPager.paddingTop,
                binding.viewPager.paddingRight,
                navBars.bottom
            )

            insets
        }

        val fragments = listOf(
            BackupFragment(),
            RestoreFragment(),
            ConfigFragment()
        )

        binding.viewPager.adapter = TabAdapter(this, fragments)
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.offscreenPageLimit = 2

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_backup -> binding.viewPager.currentItem = 0
                R.id.nav_restore -> binding.viewPager.currentItem = 1
                R.id.nav_config -> binding.viewPager.currentItem = 2
            }
            true
        }

        // Sync ViewPager -> BottomNav + Toolbar title
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.topAppBar.title = pageTitles[position]
            }
        })
    }

    private class TabAdapter(
        activity: FragmentActivity,
        private val fragments: List<Fragment>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = fragments.size
        override fun createFragment(position: Int): Fragment = fragments[position]
    }
}
