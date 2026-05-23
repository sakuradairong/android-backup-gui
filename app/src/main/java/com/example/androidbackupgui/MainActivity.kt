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
import com.example.androidbackupgui.ui.BackupFragment
import com.example.androidbackupgui.ui.ConfigFragment
import com.example.androidbackupgui.ui.RestoreFragment
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pageTitles = listOf("应用备份", "应用恢复", "备份配置")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DynamicColors.applyToActivitiesIfAvailable(application)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request root access on startup
        lifecycleScope.launch(Dispatchers.IO) {
            RootShell.ensureSession()
        }

        // Edge-to-edge: pad toolbar below status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.topAppBar) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        val fragments = listOf(
            BackupFragment(),
            RestoreFragment(),
            ConfigFragment()
        )

        binding.viewPager.adapter = TabAdapter(this, fragments)
        binding.viewPager.isUserInputEnabled = true

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
