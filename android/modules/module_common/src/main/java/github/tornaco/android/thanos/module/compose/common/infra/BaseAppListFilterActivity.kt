package github.tornaco.android.thanos.module.compose.common.infra

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.AndroidEntryPoint
import github.tornaco.android.thanos.module.compose.common.ComposeThemeActivity
import github.tornaco.android.thanos.module.compose.common.OnResumeEffect

@AndroidEntryPoint
abstract class BaseAppListFilterActivity : ComposeThemeActivity() {
    companion object {
        const val KEY_FEAT_ID = "feature.id"

        @JvmStatic
        fun start(context: Context) {
            val starter = Intent(context, BaseAppListFilterActivity::class.java)
            context.startActivity(starter)
        }
    }

    @Composable
    override fun Content() {
        val featId = intent.getIntExtra(KEY_FEAT_ID, -1)
        // 用于强制重新执行 getConfig
        var configKey by remember { mutableIntStateOf(0) }

        @Composable
        fun getConfigWithKey(featId: Int, key: Int): BaseAppListFilterContainerConfig {
            return getConfig(featId) // 原始方法内部可以感知 key 的变化
        }

        val config = getConfigWithKey(featId, configKey)
        OnResumeEffect {
            configKey++
        }

        BaseAppListFilterContent(config)
    }

    abstract fun getConfig(featureId: Int): BaseAppListFilterContainerConfig
}

data class BaseAppListFilterContainerConfig(
    val featureId: String,
    val appBarConfig: AppBarConfig,
    val appItemConfig: AppItemConfig,
    val featureDescription: (Context) -> String? = { null },
    val fabs: List<FabItemConfig> = emptyList(),
    val switchBarConfig: SwitchBarConfig? = null,
    val batchOperationConfig: BatchOperationConfig? = null
)

data class AppBarConfig(
    val title: (Context) -> String,
    val actions: (Context) -> List<AppBarAction> = { emptyList() }
) {
    data class AppBarAction(
        val title: String,
        val icon: Int,
        val onClick: () -> Unit,
    )
}

data class FabItemConfig(
    val title: (Context) -> String,
    val onClick: () -> Unit
)

data class BatchOperationConfig(
    val operations: List<Operation>
) {
    data class Operation(
        val title: (Context) -> String,
        val onClick: suspend (List<AppUiModel>) -> Unit
    )
}

data class SwitchBarConfig(
    val title: (Context, Boolean) -> String,
    val isChecked: Boolean,
    val onCheckChanged: (Boolean) -> Boolean
)

data class AppItemConfig(
    val itemType: ItemType,
    val loader: suspend (Context, pkgSetId: String) -> List<AppUiModel>,
) {
    sealed interface ItemType {
        data class Plain(
            val onAppClick: (AppUiModel) -> Unit
        ) : ItemType

        data class Checkable(
            val onCheckChanged: (AppUiModel, Boolean) -> Unit,
        ) : ItemType

        data class OptionSelectable(
            val options: List<Option>,
            val onSelected: (AppUiModel, String) -> Unit
        ) : ItemType {
            data class Option(
                val title: (Context) -> String,
                val iconRes: Int,
                val iconTintColor: Color,
                val id: String,
                val summary: String? = null,
                val showOnAppListItem: Boolean = true,
            )
        }

    }
}