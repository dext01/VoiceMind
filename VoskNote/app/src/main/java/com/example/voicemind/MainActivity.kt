package com.example.voicemind

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    var llmHelper: LlmHelper? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "Запись" else "История"
        }.attach()

        // Загружаем LLM один раз для всего приложения
        thread {
            val llm = LlmHelper(applicationContext)
            if (llm.load()) {
                llmHelper = llm
                Log.i("MainActivity", "LLM ready: ${llm.loadedModelName}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        llmHelper?.release()
        llmHelper = null
    }
}
