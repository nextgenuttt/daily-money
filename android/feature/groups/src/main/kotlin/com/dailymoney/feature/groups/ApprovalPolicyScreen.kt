package com.dailymoney.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.sharing.ApprovalMode
import com.dailymoney.core.domain.sharing.ApprovalPolicy
import com.dailymoney.core.domain.sharing.ApprovalRule
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.RuleType
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The three CONDITIONAL rules as edited on screen. */
data class RuleForm(
    val amountOn: Boolean = false,
    val amountText: String = "",
    val categoriesOn: Boolean = false,
    val categoriesText: String = "",
    val creatorsOn: Boolean = false,
    val creatorIds: Set<String> = emptySet(),
)

data class PolicyUiState(
    val loading: Boolean = true,
    val error: GroupError? = null,
    val policy: ApprovalPolicy? = null,
    val rules: RuleForm = RuleForm(),
    val members: List<GroupPerson> = emptyList(),
    val currency: String = "INR",
    val saving: Boolean = false,
    val saveError: GroupError? = null,
    val saved: Boolean = false,
    val invalid: Boolean = false,
)

@HiltViewModel
class ApprovalPolicyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupRepository,
    shared: SharedExpenseRepository,
) : ViewModel() {

    private val groupId = savedStateHandle.toRoute<ApprovalPolicyRoute>().groupId
    private val edit = MutableStateFlow(PolicyUiState())

    val state: StateFlow<PolicyUiState> = combine(edit, shared.observeGroups()) { e, groups ->
        val group = groups.firstOrNull { it.id == groupId }
        e.copy(members = group?.members.orEmpty(), currency = group?.currency ?: e.currency)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), edit.value)

    init {
        load()
    }

    fun load() {
        edit.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.approvalPolicy(groupId)) {
                is GroupResult.Ok -> edit.update { it.copy(loading = false, policy = r.value, rules = r.value.toForm(state.value.currency)) }
                is GroupResult.Err -> edit.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    private fun change(block: (ApprovalPolicy) -> ApprovalPolicy) =
        edit.update { s -> s.copy(policy = s.policy?.let(block), saved = false, saveError = null, invalid = false) }

    private fun changeRules(block: (RuleForm) -> RuleForm) = edit.update { it.copy(rules = block(it.rules), saved = false, saveError = null, invalid = false) }

    fun setMode(mode: ApprovalMode) = change { it.copy(mode = mode) }
    fun toggleApprover(id: String) = change { p ->
        val ids = if (id in p.approverIds) p.approverIds - id else p.approverIds + id
        p.copy(approverIds = ids, requiredApprovals = p.requiredApprovals.coerceIn(1, maxOf(1, ids.size)))
    }
    fun setRequired(n: Int) = change { it.copy(requiredApprovals = n) }
    fun setSelfApproval(on: Boolean) = change { it.copy(allowSelfApproval = on) }
    fun setReapprove(on: Boolean) = change { it.copy(reapproveOnMaterialChange = on) }
    fun setAmountOn(on: Boolean) = changeRules { it.copy(amountOn = on) }
    fun setAmount(text: String) = changeRules { it.copy(amountText = text.filter { c -> c.isDigit() || c == '.' }.take(14)) }
    fun setCategoriesOn(on: Boolean) = changeRules { it.copy(categoriesOn = on) }
    fun setCategories(text: String) = changeRules { it.copy(categoriesText = text.take(600)) }
    fun setCreatorsOn(on: Boolean) = changeRules { it.copy(creatorsOn = on) }
    fun toggleCreator(id: String) = changeRules { it.copy(creatorIds = if (id in it.creatorIds) it.creatorIds - id else it.creatorIds + id) }

    fun save() {
        val s = state.value
        val policy = s.policy ?: return
        val rules = if (policy.mode == ApprovalMode.CONDITIONAL) s.rules.toRules(s.currency) else emptyList()
        if (rules == null || (policy.mode == ApprovalMode.CONDITIONAL && rules.isEmpty()) ||
            (policy.mode != ApprovalMode.INSTANT && policy.approverIds.isEmpty())
        ) {
            edit.update { it.copy(invalid = true) }
            return
        }
        edit.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            when (val r = repository.saveApprovalPolicy(groupId, policy.copy(rules = rules))) {
                is GroupResult.Ok -> edit.update { it.copy(saving = false, saved = true, policy = r.value) }
                is GroupResult.Err -> edit.update { it.copy(saving = false, saveError = r.error) }
            }
        }
    }
}

private fun ApprovalPolicy.toForm(currency: String): RuleForm {
    val amount = rules.firstOrNull { it.type == RuleType.AMOUNT_GTE }
    val categories = rules.firstOrNull { it.type == RuleType.CATEGORY_IN }
    val creators = rules.firstOrNull { it.type == RuleType.CREATOR_IN }
    return RuleForm(
        amountOn = amount != null,
        amountText = amount?.amountMinor?.let { MoneyFormatter.toInput(it, currency) }.orEmpty(),
        categoriesOn = categories != null,
        categoriesText = categories?.categories?.joinToString(", ").orEmpty(),
        creatorsOn = creators != null,
        creatorIds = creators?.userIds?.toSet().orEmpty(),
    )
}

/** Null when a switched-on rule is incomplete. */
internal fun RuleForm.toRules(currency: String): List<ApprovalRule>? {
    val rules = mutableListOf<ApprovalRule>()
    if (amountOn) rules += ApprovalRule(RuleType.AMOUNT_GTE, amountMinor = MoneyParser.parse(amountText, currency)?.takeIf { it > 0 } ?: return null)
    if (categoriesOn) {
        val names = categoriesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null
        rules += ApprovalRule(RuleType.CATEGORY_IN, categories = names)
    }
    if (creatorsOn) {
        if (creatorIds.isEmpty()) return null
        rules += ApprovalRule(RuleType.CREATOR_IN, userIds = creatorIds.sorted())
    }
    return rules
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalPolicyScreen(onBack: () -> Unit, viewModel: ApprovalPolicyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.approval_rules)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val policy = state.policy
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        if (policy == null) {
            state.error?.let { ErrorBanner(it.message(), Modifier.padding(padding).padding(Spacing.md)) }
            return@Scaffold
        }
        val editable = policy.canEdit
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (!editable) Text(stringResource(R.string.approval_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ApprovalMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = editable) { viewModel.setMode(mode) },
                ) {
                    RadioButton(selected = policy.mode == mode, onClick = null, enabled = editable)
                    Column(Modifier.padding(start = Spacing.sm)) {
                        Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                        Text(mode.hint(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (policy.mode == ApprovalMode.CONDITIONAL) Conditions(state, editable, viewModel)
            if (policy.mode != ApprovalMode.INSTANT) {
                Text(stringResource(R.string.approval_approvers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
                if (policy.eligibleApprovers.isEmpty()) Text(stringResource(R.string.approval_no_eligible), style = MaterialTheme.typography.bodySmall)
                policy.eligibleApprovers.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in policy.approverIds, onCheckedChange = { viewModel.toggleApprover(id) }, enabled = editable)
                        Text(name)
                    }
                }
                if (policy.approverIds.size > 1) {
                    Text(stringResource(R.string.approval_required), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        (1..minOf(5, policy.approverIds.size)).forEach { n ->
                            FilterChip(policy.requiredApprovals == n, { if (editable) viewModel.setRequired(n) }, { Text("$n") })
                        }
                    }
                }
                Toggle(stringResource(R.string.approval_self), policy.allowSelfApproval, editable, viewModel::setSelfApproval)
                Toggle(stringResource(R.string.approval_reapprove), policy.reapproveOnMaterialChange, editable, viewModel::setReapprove)
            }
            if (state.invalid) ErrorBanner(stringResource(R.string.approval_incomplete))
            state.saveError?.let { ErrorBanner(it.message()) }
            if (state.saved) Text(stringResource(R.string.approval_saved), color = MaterialTheme.colorScheme.primary)
            if (editable) PrimaryButton(stringResource(R.string.save), viewModel::save, loading = state.saving, modifier = Modifier.padding(vertical = Spacing.md))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conditions(state: PolicyUiState, editable: Boolean, viewModel: ApprovalPolicyViewModel) {
    val rules = state.rules
    Text(stringResource(R.string.approval_conditions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
    Toggle(stringResource(R.string.approval_rule_amount), rules.amountOn, editable, viewModel::setAmountOn)
    if (rules.amountOn) {
        OutlinedTextField(
            rules.amountText, viewModel::setAmount, enabled = editable, singleLine = true,
            prefix = { Text(Currencies.symbol(state.currency)) },
            label = { Text(stringResource(R.string.approval_rule_amount_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    Toggle(stringResource(R.string.approval_rule_categories), rules.categoriesOn, editable, viewModel::setCategoriesOn)
    if (rules.categoriesOn) {
        OutlinedTextField(
            rules.categoriesText, viewModel::setCategories, enabled = editable,
            label = { Text(stringResource(R.string.approval_rule_categories_label)) },
        )
    }
    Toggle(stringResource(R.string.approval_rule_people), rules.creatorsOn, editable, viewModel::setCreatorsOn)
    if (rules.creatorsOn) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.members.forEach { m -> FilterChip(m.userId in rules.creatorIds, { if (editable) viewModel.toggleCreator(m.userId) }, { Text(m.name) }) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ApprovalMode.label() = stringResource(
    when (this) {
        ApprovalMode.INSTANT -> R.string.approval_mode_instant
        ApprovalMode.REQUIRE_APPROVAL -> R.string.approval_mode_always
        ApprovalMode.CONDITIONAL -> R.string.approval_mode_conditional
    },
)

@Composable
private fun ApprovalMode.hint() = stringResource(
    when (this) {
        ApprovalMode.INSTANT -> R.string.approval_mode_instant_hint
        ApprovalMode.REQUIRE_APPROVAL -> R.string.approval_mode_always_hint
        ApprovalMode.CONDITIONAL -> R.string.approval_mode_conditional_hint
    },
)
