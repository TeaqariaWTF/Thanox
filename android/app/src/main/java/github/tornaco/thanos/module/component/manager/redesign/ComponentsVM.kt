package github.tornaco.thanos.module.component.manager.redesign

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.elvishew.xlog.XLog
import github.tornaco.android.thanos.BuildProp
import github.tornaco.android.thanos.core.app.ThanosManager
import github.tornaco.android.thanos.core.pm.AppInfo
import github.tornaco.android.thanos.core.pm.ComponentInfo
import github.tornaco.android.thanos.core.pm.ComponentUtil
import github.tornaco.android.thanos.core.pm.Pkg
import github.tornaco.android.thanos.module.compose.common.infra.UiState
import github.tornaco.android.thanos.res.R
import github.tornaco.thanos.module.component.manager.model.ComponentModel
import github.tornaco.thanos.module.component.manager.redesign.rule.BlockerRules.classNameToRule
import github.tornaco.thanos.module.component.manager.redesign.rule.ComponentRule
import github.tornaco.thanos.module.component.manager.redesign.rule.RuleInit
import github.tornaco.thanos.module.component.manager.redesign.rule.fallbackRuleCategory
import github.tornaco.thanos.module.component.manager.redesign.rule.getActivityRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import util.PinyinComparatorUtils
import java.util.UUID

private const val PREFS_KEY_VIEW_TYPE = "ComponentList.ViewType"

enum class FilterState {
    All,
    Enabled,
    Disabled,
}

data class ComponentGroup(
    val ruleCategory: ComponentRuleCategory = fallbackRuleCategory,
    val components: List<ComponentModel> = emptyList(),
    val id: String = UUID.randomUUID().toString()
)

data class ComponentRuleCategory(
    val label: String,
    @DrawableRes val iconRes: Int,
    val isSimpleColorIcon: Boolean,
)

fun ComponentRule.toCategory() = ComponentRuleCategory(
    label,
    iconRes,
    isSimpleColorIcon
)

data class MultipleSelectState(
    val isSelectMode: Boolean = false,
    val selectedItems: Set<ComponentModel> = emptySet(),
)

data class BatchOpState(
    val isWorking: Boolean = false,
    val progressText: String = ""
)

enum class ViewType {
    Categorized,
    Flatten
}

@SuppressLint("StaticFieldLeak")
abstract class ComponentsVM(
    val context: Context,
) :
    ViewModel() {
    private val thanox: ThanosManager by lazy { ThanosManager.from(context) }

    private val _appInfo = MutableStateFlow(AppInfo.dummy())
    private val _searchQuery = MutableStateFlow("")
    private val _refresh = MutableStateFlow(System.currentTimeMillis())

    val collapsedGroups = MutableStateFlow(emptySet<String>())

    val selectState = MutableStateFlow(MultipleSelectState())
    val batchOpState = MutableStateFlow(BatchOpState())
    val filterState = MutableStateFlow(FilterState.All)
    val viewType = MutableStateFlow(
        PreferenceManager.getDefaultSharedPreferences(context).getInt(
            PREFS_KEY_VIEW_TYPE, ViewType.Categorized.ordinal
        ).let {
            runCatching { ViewType.entries[it] }.getOrDefault(ViewType.Categorized)
        })

    val components =
        combineTransform<AppInfo, String, FilterState, ViewType, Long, UiState<List<ComponentGroup>>>(
            _appInfo,
            _searchQuery,
            filterState,
            viewType,
            _refresh,
            transform = { appInfo, query, filterState, viewType, _ ->
                emit(UiState.Loading)
                RuleInit.init(context)

                kotlin.runCatching {
                    emit(
                        UiState.Loaded(
                            loadComponentsGroups(
                                appInfo = appInfo,
                                filterState = filterState,
                                query = query,
                                viewType = viewType
                            )
                        )
                    )
                }.onFailure {
                    emit(UiState.Error(it))
                }
            }
        ).onEach { uiState ->
            if (uiState is UiState.Loaded) {
                val fallbackGroup =
                    uiState.data.firstOrNull { it.ruleCategory == fallbackRuleCategory }
                val fallbackSize =
                    fallbackGroup?.components?.size ?: 0
                if (fallbackSize > 100) {
                    fallbackGroup?.let { expand(it, false) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, initialValue = UiState.Loading)

    private suspend fun loadComponentsGroups(
        appInfo: AppInfo,
        filterState: FilterState,
        query: String,
        viewType: ViewType
    ): List<ComponentGroup> {
        return withContext(Dispatchers.IO) {
            val res: MutableList<ComponentModel> = ArrayList()
            for (i in 0 until Int.MAX_VALUE) {
                val batch =
                    thanox.getComponentsInBatch(
                        /* userId = */ appInfo.userId,
                        /* packageName = */ appInfo.pkgName,
                        /* itemCountInEachBatch = */ 20,
                        /* batchIndex = */ i
                    ) ?: break
                batch
                    .filter {
                        filterState == FilterState.All
                                || filterState == FilterState.Enabled && ComponentUtil.isComponentEnabled(
                            it.enableSetting
                        )
                                || filterState == FilterState.Disabled && ComponentUtil.isComponentDisabled(
                            it.enableSetting
                        )
                    }
                    .filter {
                        TextUtils.isEmpty(query) || it.name.lowercase().contains(query.lowercase())
                    }
                    .forEach { info ->
                        res.add(
                            ComponentModel(
                                /* name = */ info.name,
                                /* componentName = */
                                info.componentName,
                                /* label = */
                                info.label,
                                /* enableSetting = */
                                info.enableSetting,
                                /* componentObject = */
                                info,
                                /* isDisabledByThanox = */
                                info.isDisabledByThanox,
                                /* isRunning = */
                                false,
                                /* componentRule = */
                                getActivityRule(info.componentName),
                                /* blockerRule = */
                                info.componentName.className.classNameToRule().also {
                                    if (BuildProp.THANOS_BUILD_DEBUG) {
                                        XLog.d("classNameToRule: ${info.componentName.className} $it")
                                    }
                                }
                            )
                        )
                    }
            }

            res.sort()

            if (viewType == ViewType.Categorized) {
                res.groupBy { it.componentRule.toCategory() }.toSortedMap { o1, o2 ->
                    if (o1 == fallbackRuleCategory && o2 != fallbackRuleCategory) return@toSortedMap 1
                    if (o1 != fallbackRuleCategory && o2 == fallbackRuleCategory) return@toSortedMap -1
                    PinyinComparatorUtils.compare(
                        o1?.label.orEmpty(),
                        o2?.label.orEmpty()
                    )
                }.map {
                    ComponentGroup(it.key, it.value)
                }
            } else {
                listOf(
                    ComponentGroup(
                        ruleCategory = ComponentRuleCategory(
                            context.getString(R.string.all),
                            0,
                            false
                        ),
                        components = res
                    )
                )
            }
        }
    }

    abstract fun ThanosManager.getComponentsInBatch(
        userId: Int, packageName: String,
        itemCountInEachBatch: Int,
        batchIndex: Int
    ): List<ComponentInfo>?

    fun initApp(appInfo: AppInfo) {
        _appInfo.update { appInfo }
    }

    fun search(query: String) {
        _searchQuery.update { query }
    }

    fun refresh() {
        _refresh.update { System.currentTimeMillis() }
    }

    fun setFilter(filter: FilterState) {
        filterState.update {
            filter
        }
    }

    fun toggleViewType() {
        setViewType(
            if (viewType.value == ViewType.Categorized) {
                ViewType.Flatten
            } else {
                ViewType.Categorized
            }
        )
    }

    private fun setViewType(type: ViewType) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putInt(PREFS_KEY_VIEW_TYPE, type.ordinal)
        }
        viewType.update { type }
    }

    fun toggleExpandAll() {
        val willExpandAll = collapsedGroups.value.isNotEmpty()
        if (willExpandAll) {
            collapsedGroups.update { emptySet() }
        } else {
            (components.value as? UiState.Loaded)?.let { loaded ->
                loaded.data.forEach {
                    expand(it, false)
                }
            }
        }
    }

    fun expand(group: ComponentGroup, expand: Boolean) {
        collapsedGroups.update {
            if (expand) {
                it.minus(group.id)
            } else {
                it.plus(group.id)
            }
        }
    }

    fun exitSelectionState() {
        selectState.update {
            it.copy(
                isSelectMode = false,
                selectedItems = emptySet()
            )
        }
    }

    fun select(model: ComponentModel, select: Boolean) {
        val updatedItems = if (select) {
            selectState.value.selectedItems + model
        } else {
            selectState.value.selectedItems - model
        }
        val isSelectMode = updatedItems.isNotEmpty()
        selectState.update {
            it.copy(
                selectedItems = updatedItems,
                isSelectMode = isSelectMode
            )
        }
    }

    fun select(group: ComponentGroup, select: Boolean) {
        val updatedItems = if (select) {
            selectState.value.selectedItems + group.components
        } else {
            selectState.value.selectedItems - group.components.toSet()
        }
        val isSelectMode = updatedItems.isNotEmpty()
        selectState.update {
            it.copy(
                selectedItems = updatedItems,
                isSelectMode = isSelectMode
            )
        }
    }

    fun setComponentState(
        componentModel: ComponentModel,
        setToEnabled: Boolean
    ): Boolean {
        XLog.w("setComponentState: $componentModel $setToEnabled")
        val appInfo = _appInfo.value
        val newState =
            if (setToEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (newState == componentModel.enableSetting) {
            return false
        }
        if (thanox.isServiceInstalled) {
            componentModel.enableSetting = newState
            thanox.pkgManager.setComponentEnabledSetting(
                appInfo.userId,
                componentModel.componentName,
                newState,
                0 /* Kill it */
            )
            return true
        }
        return false
    }

    fun appBatchOp(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val appInfo = _appInfo.value
                val modelList = selectState.value.selectedItems.toList()
                val totalCount = modelList.size
                thanox.activityManager.forceStopPackage(
                    Pkg.fromAppInfo(
                        appInfo
                    ), "ComponentList UI selectAll"
                )
                // Wait 1s.
                batchOpState.update { it.copy(isWorking = true, progressText = "") }
                for (i in modelList.indices) {
                    val componentModel = modelList[i]
                    batchOpState.update {
                        it.copy(
                            progressText = (i + 1).toString() + "/" + totalCount
                        )
                    }
                    if (enabled != componentModel.isEnabled && setComponentState(
                            componentModel,
                            enabled
                        )
                    ) {
                        try {
                            // Maybe a short delay will make it safer.
                            Thread.sleep(30)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }
                batchOpState.update { it.copy(isWorking = false, progressText = "") }

                exitSelectionState()
                refresh()
            }
        }
    }

    fun selectAllAgainstBlockRules() {
        val groups = components.value as? UiState.Loaded ?: return
        batchOpState.update {
            it.copy(isWorking = true)
        }
        viewModelScope.launch {
            groups.data.flatMap { it.components }.forEach { model ->
                if (model.blockerRule?.safeToBlock == true) {
                    batchOpState.update {
                        it.copy(progressText = model.label.orEmpty())
                    }
                    select(model, true)
                }
            }
        }
        batchOpState.update {
            it.copy(isWorking = false, progressText = "")
        }
    }

    fun selectAll(select: Boolean) {
        val groups = components.value as? UiState.Loaded ?: return
        batchOpState.update {
            it.copy(isWorking = true)
        }
        if (select) {
            groups.data.forEach { select(it, true) }
        } else {
            groups.data.forEach { select(it, false) }
        }
        batchOpState.update {
            it.copy(isWorking = false, progressText = "")
        }
    }
}