package org.example.memosm.widget.stats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.Account
import org.example.memosm.model.UserStats

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UserStatsWidget : GlanceAppWidget(), KoinComponent {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val accountId = prefs[stringPreferencesKey("account_id")]

            val state by produceState<StatsState>(
                initialValue = StatsState.Loading, key1 = accountId
            ) {
                if (accountId == null) {
                    // Stay loading or handle as special non-error case in UI? 
                    // Let's assume loading until we determine it's empty in UI check
                    value = StatsState.Loading
                } else {
                    value = try {
                        withContext(Dispatchers.IO) {
                            val dataStoreManager: DataStoreManager by inject()
                            val accounts = dataStoreManager.getAccounts()
                            val account = accounts.find { it.id == accountId }

                            if (account != null) {
                                try {
                                    val client = OkHttpClient.Builder()
                                        .addInterceptor(AuthInterceptor(account.accessToken))
                                        .build()
                                    val api = MemosApiFactory.create(account.hostUrl, client)

                                    val username =
                                        account.user?.name ?: api.getCurrentSession().user?.name

                                    if (username != null) {
                                        val stats = api.getUserStats(username)
                                        StatsState.Success(stats, account)
                                    } else {
                                        StatsState.Error(R.string.widget_stats_error_user_not_found)
                                    }
                                } catch (e: Exception) {
                                    StatsState.Error(R.string.widget_stats_error_network)
                                }
                            } else {
                                StatsState.Error(R.string.widget_stats_error_account_not_found)
                            }
                        }
                    } catch (e: Exception) {
                        StatsState.Error(R.string.common_unknown_error)
                    }
                }
            }

            GlanceTheme {
                Box(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    if (accountId == null) {
                        EmptyState(context)
                    } else {
                        when (val currentState = state) {
                            is StatsState.Loading -> LoadingState(context)
                            is StatsState.Error -> ErrorState(context, currentState.messageRes)
                            is StatsState.Success -> StatsContent(
                                currentState.stats, currentState.account
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun EmptyState(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = context.getString(R.string.widget_stats_tap_to_configure),
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.clickable(actionStartActivity<UserStatsWidgetConfigActivity>())
            )
        }
    }

    @Composable
    fun LoadingState(context: Context) {
        Box(
            modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            Text(context.getString(R.string.widget_stats_loading), style = TextStyle(color = GlanceTheme.colors.onSurface))
        }
    }

    @Composable
    fun ErrorState(context: Context, @androidx.annotation.StringRes messageRes: Int) {
        Box(
            modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            Text(
                context.getString(R.string.widget_stats_error_format, context.getString(messageRes)), style = TextStyle(color = GlanceTheme.colors.error)
            )
        }
    }

    @Composable
    fun StatsContent(stats: UserStats, account: Account) {
        val context = androidx.glance.LocalContext.current
        val size = androidx.glance.LocalSize.current
        val notAvailable = context.getString(R.string.common_not_available)

        // Configuration
        val height = size.height
        val useScroll = height < 120.dp // Threshold lowered to 120dp as requested
        val useLargeFonts = height > 220.dp

        val fontScale = if (useLargeFonts) 1.3f else 1.0f

        val valueFontSize = 18.sp * fontScale
        val labelFontSize = 12.sp * fontScale
        val headerFontSize = 14.sp * fontScale

        // Increase padding between rows as requested
        val rowSpacing = 12.dp

        // Root container for centering - crucial for non-scroll layout
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = if (useScroll) Alignment.TopCenter else Alignment.Center
        ) {
            if (useScroll) {
                androidx.glance.appwidget.lazy.LazyColumn(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header (Centered explicitly in LazyColumn item)
                    item {
                        Box(
                            modifier = GlanceModifier.fillMaxWidth().padding(bottom = rowSpacing),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = context.getString(R.string.widget_stats_for_account, account.name),
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = headerFontSize
                                )
                            )
                        }
                    }
                    item {
                        Row1(context, stats, valueFontSize, labelFontSize)
                    }
                    item {
                        Spacer(GlanceModifier.height(rowSpacing))
                    }
                    item {
                        Row2(context, stats, notAvailable, valueFontSize, labelFontSize)
                    }
                }
            } else {
                Column(
                    modifier = GlanceModifier.wrapContentHeight().fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = context.getString(R.string.widget_stats_for_account, account.name),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = headerFontSize
                        ),
                        modifier = GlanceModifier.padding(bottom = rowSpacing)
                    )
                    Row1(context, stats, valueFontSize, labelFontSize)
                    Spacer(GlanceModifier.height(rowSpacing))
                    Row2(context, stats, notAvailable, valueFontSize, labelFontSize)
                }
            }
        }
    }

    @Composable
    fun Row1(
        context: Context,
        stats: UserStats,
        valueSize: androidx.compose.ui.unit.TextUnit,
        labelSize: androidx.compose.ui.unit.TextUnit
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatItem(
                label = context.getString(R.string.profile_stats_memos),
                value = stats.totalMemoCount.toString(),
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
            StatItem(
                label = context.getString(R.string.profile_stats_tags),
                value = stats.tagCount?.size?.toString() ?: "0",
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
            StatItem(
                label = context.getString(R.string.profile_stats_pinned),
                value = stats.pinnedMemos?.size?.toString() ?: "0",
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
        }
    }

    @Composable
    fun Row2(
        context: Context,
        stats: UserStats,
        notAvailable: String,
        valueSize: androidx.compose.ui.unit.TextUnit,
        labelSize: androidx.compose.ui.unit.TextUnit
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatItem(
                label = context.getString(R.string.profile_stats_links),
                value = stats.memoTypeStats?.linkCount?.toString() ?: notAvailable,
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
            StatItem(
                label = context.getString(R.string.profile_stats_code),
                value = stats.memoTypeStats?.codeCount?.toString() ?: notAvailable,
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
            StatItem(
                label = context.getString(R.string.profile_stats_todo),
                value = stats.memoTypeStats?.todoCount?.toString() ?: notAvailable,
                valueSize = valueSize,
                labelSize = labelSize,
                modifier = GlanceModifier.defaultWeight()
            )
        }
    }

    @Composable
    fun StatItem(
        label: String,
        value: String,
        valueSize: androidx.compose.ui.unit.TextUnit,
        labelSize: androidx.compose.ui.unit.TextUnit,
        modifier: GlanceModifier = GlanceModifier
    ) {
        Column(
            modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value, style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = valueSize,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = label, style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant, fontSize = labelSize
                )
            )
        }
    }

}

sealed class StatsState {
    object Loading : StatsState()
    data class Success(val stats: UserStats, val account: Account) : StatsState()
    data class Error(@param:androidx.annotation.StringRes val messageRes: Int) : StatsState()
}
