package yumo.achat.app.ui.imagevideo

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.compose.AsyncImage
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yumo.achat.app.R
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreCatalog
import yumo.achat.core.backend.PreparedStorePayment
import yumo.achat.core.backend.PaymentOrderStatus
import yumo.achat.core.backend.StoreUserInfo
import yumo.achat.core.backend.TemplateLoadResult
import yumo.achat.core.backend.VisualCategory
import yumo.achat.core.backend.VisualGenerationTask
import yumo.achat.core.backend.VisualTemplate
import yumo.achat.app.ui.components.TransientMessage
import yumo.achat.app.ui.components.TransientMessageHost
import yumo.achat.app.ui.components.TransientMessageTone
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatDeepNavy
import yumo.achat.app.ui.theme.AchatMuted
import yumo.achat.app.ui.theme.AchatPink
import yumo.achat.app.ui.theme.AchatTheme

internal enum class ImageToVideoDestination {
    Templates,
    UploadPhoto,
    MyTasks,
    Feedback,
    EditName,
}

internal data class BottomNavigationRoute(
    val selectedNavigation: Int,
    val destination: ImageToVideoDestination,
)

internal fun routeFromBottomNavigation(navigationIndex: Int): BottomNavigationRoute {
    require(navigationIndex in 0..3) { "Unsupported bottom navigation index: $navigationIndex" }
    return BottomNavigationRoute(
        selectedNavigation = navigationIndex,
        destination = ImageToVideoDestination.Templates,
    )
}

internal fun shouldRefreshTopUp(currentNavigation: Int, selectedNavigation: Int): Boolean =
    currentNavigation == 2 && selectedNavigation == 2

internal fun balanceAfterGenerationTaskCreated(currentBalance: Int, diamondCost: Int): Int =
    (currentBalance - diamondCost.coerceAtLeast(0)).coerceAtLeast(0)

internal fun shouldRefreshBalanceForTaskStatus(status: String): Boolean =
    status == "succeeded" || status == "failed"

private enum class TemplateSection {
    Video,
    Image,
}

internal data class AchatBackendUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val profileName: String = "Quiet wanderer",
    val profileId: String = "3095609813",
    val profileAvatarUrl: String? = null,
    val diamondBalance: Int = 0,
    val videoTemplates: List<VisualTemplate> = emptyList(),
    val imageTemplates: List<VisualTemplate> = emptyList(),
    val videoTemplatesLoading: Boolean = true,
    val imageTemplatesLoading: Boolean = true,
    val videoTemplateErrorMessage: String? = null,
    val imageTemplateErrorMessage: String? = null,
    val videoCategories: List<VisualCategory> = emptyList(),
    val imageCategories: List<VisualCategory> = emptyList(),
)

internal fun AchatBackendUiState.withTemplateLoadResult(
    modality: String,
    result: TemplateLoadResult,
): AchatBackendUiState = when (modality) {
    "video" -> copy(
        videoTemplates = if (result.errorMessage == null) result.templates else videoTemplates,
        videoTemplatesLoading = false,
        videoTemplateErrorMessage = result.errorMessage,
    )
    "image" -> copy(
        imageTemplates = if (result.errorMessage == null) result.templates else imageTemplates,
        imageTemplatesLoading = false,
        imageTemplateErrorMessage = result.errorMessage,
    )
    else -> error("Unsupported template modality")
}

internal fun AchatBackendUiState.withLoadedProfile(
    profile: yumo.achat.core.backend.UserProfile?,
    fallbackId: String,
    preserveCurrent: Boolean,
): AchatBackendUiState = if (preserveCurrent) {
    this
} else {
    copy(
        profileName = profile?.displayName ?: profileName,
        profileId = profile?.id ?: fallbackId,
        profileAvatarUrl = profile?.largeAvatarUrl ?: profile?.avatarUrl,
    )
}

internal fun shouldShowLocalTemplateFallback(
    templates: List<VisualTemplate>,
    isLoading: Boolean,
    errorMessage: String?,
): Boolean = templates.isEmpty() && !isLoading && errorMessage != null

internal fun visibleTemplatePrice(template: VisualTemplate?): Int? = template?.displayPrice

internal data class TopUpUiState(
    val isLoading: Boolean = false,
    val catalog: StoreCatalog? = null,
    val errorMessage: String? = null,
    val diamondBalance: Int = 0,
)

internal sealed interface TopUpPurchaseState {
    data object Idle : TopUpPurchaseState
    data class Preparing(val productId: String) : TopUpPurchaseState
    data class OfficialReady(
        val productId: String,
        val order: yumo.achat.core.backend.StoreOrder,
        val channelCode: String,
        val sdkProductId: String,
    ) : TopUpPurchaseState {
        val orderId: String get() = order.id
        val obfuscatedAccountId: String get() = order.obfuscatedAccountId
        val obfuscatedProfileId: String get() = order.obfuscatedProfileId
    }
    data class ThirdPartyReady(
        val productId: String,
        val order: yumo.achat.core.backend.StoreOrder,
        val channelCode: String,
        val openMode: String,
        val paymentUrl: String,
        val expiresAt: String?,
        val queryIntervalSeconds: Int,
        val maxQuerySeconds: Int,
    ) : TopUpPurchaseState {
        val orderId: String get() = order.id
    }
    data class Error(
        val productId: String,
        val message: String,
        val order: yumo.achat.core.backend.StoreOrder? = null,
    ) : TopUpPurchaseState
}

internal enum class TopUpPaymentOutcome { Pending, Success, Failed }

internal fun classifyTopUpPayment(status: PaymentOrderStatus): TopUpPaymentOutcome = when {
    status.status == "paid" && status.fulfillmentStatus == "fulfilled" -> TopUpPaymentOutcome.Success
    status.status in setOf("failed", "cancelled", "expired") -> TopUpPaymentOutcome.Failed
    else -> TopUpPaymentOutcome.Pending
}

internal fun PreparedStorePayment.toTopUpPurchaseState(productId: String): TopUpPurchaseState {
    check(order.id == initialization.orderId) { "Payment initialization order mismatch" }
    check(order.productId == productId) { "Payment order product mismatch" }
    return when (initialization.channelType) {
        "official" -> {
            check(initialization.openMode == "sdk") { "Official payment must use sdk mode" }
            check(initialization.sdkProductId.isNotBlank()) { "Official payment product id is missing" }
            TopUpPurchaseState.OfficialReady(
                productId = productId,
                order = order,
                channelCode = initialization.channelCode,
                sdkProductId = initialization.sdkProductId,
            )
        }
        "third_party" -> {
            check(initialization.openMode == "webview" || initialization.openMode == "external_browser") {
                "Unsupported third-party open mode"
            }
            check(initialization.paymentUrl.isNotBlank()) { "Third-party payment url is missing" }
            val checkoutUri = runCatching { URI(initialization.paymentUrl) }.getOrNull()
            check(checkoutUri?.scheme == "https" && !checkoutUri.host.isNullOrBlank()) {
                "Third-party payment url must use https"
            }
            TopUpPurchaseState.ThirdPartyReady(
                productId = productId,
                order = order,
                channelCode = initialization.channelCode,
                openMode = initialization.openMode,
                paymentUrl = initialization.paymentUrl,
                expiresAt = initialization.expiresAt,
                queryIntervalSeconds = initialization.queryIntervalSeconds,
                maxQuerySeconds = initialization.maxQuerySeconds,
            )
        }
        else -> error("Unsupported payment channel type: ${initialization.channelType}")
    }
}

private data class SelectedGenerationTemplate(
    val templateId: String,
    val modality: String,
    val quality: String,
    val title: String,
    val previewMedia: TemplatePreviewMedia,
    val durationSeconds: Int,
)

internal data class CreditPack(
    val id: String,
    val credits: Int,
    val description: String,
    val price: String,
    val originalPrice: String?,
    val tier: Int,
    val accentColor: Color,
    val bonus: Int? = null,
    val badgeText: String? = null,
    val iconUrl: String? = null,
    val badgeTextRes: Int? = null,
) {
    val tierLabel: String
        get() = "TIER // ${tier.toString().padStart(2, '0')}"
}

internal fun creditPackHeightDp(selected: Boolean): Int = if (selected) 148 else 86

internal fun StoreProduct.toCreditPackPresentation(
    userInfo: StoreUserInfo?,
    displayIndex: Int,
    locale: Locale = Locale.getDefault(),
): CreditPack {
    val firstBuyEligible = userInfo?.hasMadeFirstPurchase == false && isFirstBuyPromotion
    val effectivePrice = firstBuyPrice.takeIf { firstBuyEligible && it > BigDecimal.ZERO } ?: price
    val comparisonPrice = (if (firstBuyEligible) listOf(price, originalPrice).maxOrNull() else originalPrice)
        ?.takeIf { it > BigDecimal.ZERO && it > effectivePrice }
    val totalBonus = bonusValue + if (firstBuyEligible) firstBuyBonusValue else 0
    val accents = listOf(AchatCyan, AchatPink, Color(0xFF7D55E9), AchatCyan, Color.White)
    return CreditPack(
        id = id,
        credits = value,
        description = description,
        price = formatStoreMoney(effectivePrice, currency, locale),
        originalPrice = comparisonPrice?.let { formatStoreMoney(it, currency, locale) },
        tier = displayIndex + 1,
        accentColor = accents[displayIndex % accents.size],
        bonus = totalBonus.takeIf { it > 0 },
        badgeText = tags.takeIf { it.isNotBlank() },
        iconUrl = icon.takeIf { it.isNotBlank() },
    )
}

internal fun formatStoreMoney(amount: BigDecimal, currencyCode: String, locale: Locale): String {
    val formatter = NumberFormat.getCurrencyInstance(locale)
    val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        ?: return "${currencyCode.ifBlank { "?" }} ${amount.setScale(2, java.math.RoundingMode.HALF_UP)}"
    formatter.currency = currency
    val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    formatter.minimumFractionDigits = fractionDigits
    formatter.maximumFractionDigits = fractionDigits
    formatter.roundingMode = java.math.RoundingMode.HALF_UP
    return formatter.format(amount)
}

@Composable
fun ImageToVideoScreen(modifier: Modifier = Modifier) {
    val localContext = LocalContext.current
    val context = localContext.applicationContext
    val repository = remember(context) { createAchatRepository(context) }
    val screenScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentTemplate by rememberSaveable { mutableIntStateOf(1) }
    var isPlaying by rememberSaveable { mutableStateOf(true) }
    var selectedNavigation by rememberSaveable { mutableIntStateOf(0) }
    var backendState by remember { mutableStateOf(AchatBackendUiState()) }
    val topUpPaymentViewModel: TopUpPaymentViewModel = viewModel()
    val topUpPaymentController = topUpPaymentViewModel.controller
    val profileEditingViewModel: ProfileEditingViewModel = viewModel()
    val profileEditingController = profileEditingViewModel.controller
    var topUpState by remember {
        mutableStateOf(
            TopUpUiState(
                isLoading = true,
                diamondBalance = backendState.diamondBalance,
            ),
        )
    }
    var topUpRefreshSerial by remember { mutableIntStateOf(0) }
    var destination by rememberSaveable { mutableStateOf(ImageToVideoDestination.Templates) }
    var selectedGenerationTemplate by remember { mutableStateOf<SelectedGenerationTemplate?>(null) }
    var sessionTasks by remember { mutableStateOf<List<TrackedGenerationTask>>(emptyList()) }
    var serverHistoryTasks by remember { mutableStateOf<List<TrackedGenerationTask>>(emptyList()) }
    var selectedResultTask by remember { mutableStateOf<TrackedGenerationTask?>(null) }
    var isLoadingTaskHistory by remember { mutableStateOf(false) }
    var taskHistoryError by remember { mutableStateOf<String?>(null) }
    var templateEdgeHintRes by remember { mutableStateOf<Int?>(null) }
    var templateEdgeHintSerial by remember { mutableIntStateOf(0) }
    var profileMessage by remember { mutableStateOf<TransientMessage?>(null) }
    var profileMessageSerial by remember { mutableStateOf(0L) }
    var profileRevision by remember { mutableStateOf(0L) }
    var chargedGenerationTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var diamondBalanceRefreshSerial by remember { mutableStateOf(0L) }
    val defaultTaskTitle = stringResource(R.string.default_task_title)
    val trackedTasks = mergeTrackedGenerationTasks(sessionTasks, serverHistoryTasks)
    val templateEdgeHint = templateEdgeHintRes?.let { messageRes ->
        TransientMessage(
            id = templateEdgeHintSerial.toLong(),
            text = stringResource(messageRes),
        )
    }

    fun applyProfile(profile: yumo.achat.core.backend.UserProfile) {
        profileRevision += 1
        backendState = backendState.copy(
            profileName = profile.displayName,
            profileId = profile.id,
            profileAvatarUrl = profile.largeAvatarUrl ?: profile.avatarUrl,
        )
    }

    fun showProfileMessage(text: String, tone: TransientMessageTone) {
        profileMessageSerial += 1
        profileMessage = TransientMessage(profileMessageSerial, text, tone)
    }

    val avatarUpdatedMessage = stringResource(R.string.profile_avatar_updated)
    val avatarUpdateFailedMessage = stringResource(R.string.profile_avatar_update_failed)
    val nameUpdatedMessage = stringResource(R.string.profile_name_updated)
    val videoTemplatesFallback = stringResource(R.string.video_templates_error)
    val imageTemplatesFallback = stringResource(R.string.image_templates_error)
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        profileEditingController.saveAvatar(uri)
    }

    fun openAvatarPicker() {
        if (!profileEditingController.canStartAvatarSave()) return
        avatarPicker.launch(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            },
        )
    }

    fun retryTemplates(section: TemplateSection) {
        val modality = if (section == TemplateSection.Video) "video" else "image"
        backendState = when (section) {
            TemplateSection.Video -> backendState.copy(videoTemplatesLoading = true)
            TemplateSection.Image -> backendState.copy(imageTemplatesLoading = true)
        }
        screenScope.launch {
            val result = repository.loadTemplates(modality)
            val fallback = if (section == TemplateSection.Video) videoTemplatesFallback else imageTemplatesFallback
            val errorMessage = result.errorMessage?.let { apiEnvelopeUserMessage(it, fallback) }
            backendState = backendState.withTemplateLoadResult(
                modality = modality,
                result = result.copy(errorMessage = errorMessage),
            )
        }
    }

    fun refreshDiamondBalance() {
        diamondBalanceRefreshSerial += 1
        val requestSerial = diamondBalanceRefreshSerial
        screenScope.launch {
            try {
                val currency = repository.userCurrencySnapshot()
                if (shouldApplyCurrencySnapshot(requestSerial, diamondBalanceRefreshSerial)) {
                    backendState = backendState.copy(diamondBalance = currency.diamondBalance)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the last visible balance; the next refresh opportunity reconciles it.
            }
        }
    }

    fun handleGenerationTaskCreated(task: VisualGenerationTask, trackedTask: TrackedGenerationTask) {
        sessionTasks = upsertTrackedGenerationTask(sessionTasks, trackedTask)
        if (shouldApplyGenerationCharge(task.taskId, chargedGenerationTaskIds)) {
            chargedGenerationTaskIds = chargedGenerationTaskIds + task.taskId
            backendState = backendState.copy(
                diamondBalance = balanceAfterGenerationTaskCreated(
                    currentBalance = backendState.diamondBalance,
                    diamondCost = task.diamondCost,
                ),
            )
        }
        refreshDiamondBalance()
    }

    LaunchedEffect(profileEditingController.completionSerial) {
        if (profileEditingController.completionSerial == 0) return@LaunchedEffect
        val operation = profileEditingController.completedOperation ?: return@LaunchedEffect
        val error = profileEditingController.completionError
        if (error == null) {
            profileEditingController.updatedProfile?.let(::applyProfile)
        }
        when (operation) {
            ProfileEditOperation.Name -> if (error == null) {
                destination = ImageToVideoDestination.Templates
                showProfileMessage(nameUpdatedMessage, TransientMessageTone.Success)
            }
            ProfileEditOperation.Avatar -> showProfileMessage(
                if (error == null) avatarUpdatedMessage else apiEnvelopeUserMessage(error, avatarUpdateFailedMessage),
                if (error == null) TransientMessageTone.Success else TransientMessageTone.Error,
            )
        }
        profileEditingController.acknowledgeCompletion()
    }

    fun showTemplateEdgeHint(direction: TemplateFeedDirection) {
        templateEdgeHintRes = when (direction) {
            TemplateFeedDirection.Previous -> R.string.template_feed_start
            TemplateFeedDirection.Next -> R.string.template_feed_end
        }
        templateEdgeHintSerial += 1
    }

    fun moveTemplate(direction: TemplateFeedDirection, totalItems: Int) {
        val result = moveTemplateFeedIndex(
            currentIndex = currentTemplate,
            totalItems = totalItems,
            direction = direction,
        )
        currentTemplate = result.index
        if (result.reachedEdge) {
            showTemplateEdgeHint(direction)
        } else {
            templateEdgeHintRes = null
        }
    }

    fun openMyTasks() {
        selectedResultTask = null
        isLoadingTaskHistory = true
        taskHistoryError = null
        destination = ImageToVideoDestination.MyTasks
    }

    fun navigateFromBottomNavigation(navigationIndex: Int) {
        val route = routeFromBottomNavigation(navigationIndex)
        if (shouldRefreshTopUp(selectedNavigation, navigationIndex)) {
            topUpRefreshSerial += 1
        } else if (navigationIndex == 2) {
            topUpState = TopUpUiState(
                isLoading = true,
                diamondBalance = backendState.diamondBalance,
            )
        }
        selectedNavigation = route.selectedNavigation
        selectedTab = 0
        currentTemplate = 1
        templateEdgeHintRes = null
        selectedResultTask = null
        destination = route.destination
    }

    LaunchedEffect(context) {
        val profileRevisionAtStart = profileRevision
        backendState = backendState.copy(isLoading = true, errorMessage = null)
        runCatching {
            repository.loadHomeData()
        }.onSuccess { homeData ->
            backendState = backendState.withLoadedProfile(
                profile = homeData.profile,
                fallbackId = homeData.session.userId,
                preserveCurrent = profileRevision != profileRevisionAtStart,
            ).copy(
                isLoading = false,
                diamondBalance = homeData.currency?.diamondBalance ?: backendState.diamondBalance,
                videoTemplates = homeData.videoTemplates,
                imageTemplates = homeData.imageTemplates,
                videoTemplatesLoading = false,
                imageTemplatesLoading = false,
                videoTemplateErrorMessage = homeData.videoTemplateErrorMessage?.let {
                    apiEnvelopeUserMessage(it, videoTemplatesFallback)
                },
                imageTemplateErrorMessage = homeData.imageTemplateErrorMessage?.let {
                    apiEnvelopeUserMessage(it, imageTemplatesFallback)
                },
                videoCategories = homeData.videoCategories,
                imageCategories = homeData.imageCategories,
            )
        }.onFailure { error ->
            val rawMessage = error.message ?: "Backend unavailable"
            backendState = backendState.copy(
                isLoading = false,
                errorMessage = rawMessage,
                videoTemplatesLoading = false,
                imageTemplatesLoading = false,
                videoTemplateErrorMessage = apiEnvelopeUserMessage(rawMessage, videoTemplatesFallback),
                imageTemplateErrorMessage = apiEnvelopeUserMessage(rawMessage, imageTemplatesFallback),
            )
        }
    }

    LaunchedEffect(destination) {
        if (destination != ImageToVideoDestination.MyTasks) return@LaunchedEffect
        if (selectedResultTask != null) return@LaunchedEffect
        try {
            val resources = repository.generatedResources()
            serverHistoryTasks = resources.map { resource ->
                resource.toTrackedGenerationTask(defaultTaskTitle)
            }
            taskHistoryError = null
            isLoadingTaskHistory = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            taskHistoryError = error.message ?: "Failed to load task history"
            isLoadingTaskHistory = false
        }
    }

    LaunchedEffect(destination, selectedNavigation, topUpRefreshSerial) {
        if (destination != ImageToVideoDestination.Templates || selectedNavigation != 2) return@LaunchedEffect
        refreshDiamondBalance()
        topUpState = TopUpUiState(
            isLoading = true,
            diamondBalance = backendState.diamondBalance,
        )
        try {
            val catalog = repository.storeCatalog()
            topUpState = TopUpUiState(
                catalog = catalog,
                diamondBalance = catalog.userInfo?.currentDiamond ?: backendState.diamondBalance,
            )
            topUpPaymentController.retainAvailableProducts(catalog.products.map { it.id })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            topUpState = TopUpUiState(
                errorMessage = apiEnvelopeUserMessage(
                    error.message,
                    context.getString(R.string.top_up_error),
                ),
                diamondBalance = backendState.diamondBalance,
            )
        }
    }

    LaunchedEffect(topUpPaymentController.state, topUpPaymentController.checkoutState) {
        if (topUpPaymentController.checkoutState != TopUpCheckoutState.Idle) return@LaunchedEffect
        when (val route = topUpPaymentController.state) {
            is TopUpPurchaseState.OfficialReady -> {
                val activity = localContext as? Activity
                if (activity == null) topUpPaymentController.onCheckoutLaunchError("Activity unavailable")
                else topUpPaymentViewModel.launchOfficial(activity, route)
            }
            is TopUpPurchaseState.ThirdPartyReady -> {
                if (route.openMode == "external_browser") {
                    val opened = runCatching {
                        localContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.paymentUrl)))
                    }.isSuccess
                    if (!opened) {
                        topUpPaymentController.onCheckoutLaunchError("Unable to open checkout")
                        return@LaunchedEffect
                    }
                }
                topUpPaymentController.openThirdParty(route)
            }
            else -> Unit
        }
    }

    LaunchedEffect(topUpPaymentController.successSerial) {
        if (topUpPaymentController.successSerial > 0) {
            topUpRefreshSerial += 1
            refreshDiamondBalance()
        }
    }

    sessionTasks.filterNot { it.isFinished }.forEach { trackedTask ->
        key(trackedTask.taskId) {
            LaunchedEffect(trackedTask.taskId) {
                pollGenerationTaskUntilFinished(
                    initialTask = trackedTask,
                    waitForNextPoll = { intervalSeconds -> delay(intervalSeconds * 1_000L) },
                    fetch = repository::getVisualGenerationTask,
                    onUpdate = { updatedTask ->
                        sessionTasks = upsertTrackedGenerationTask(sessionTasks, updatedTask)
                        if (shouldRefreshBalanceForTaskStatus(updatedTask.status)) {
                            refreshDiamondBalance()
                        }
                    },
                )
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, topUpPaymentController.state, topUpPaymentController.checkoutState) {
        val observer = LifecycleEventObserver { _, event ->
            val route = topUpPaymentController.state as? TopUpPurchaseState.ThirdPartyReady
            if (event == Lifecycle.Event.ON_RESUME && route?.openMode == "external_browser") {
                topUpPaymentController.refreshPayment(route)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF10102A), AchatDeepNavy, Color(0xFF110718)),
                ),
            ),
    ) {
        BackgroundGlow()
        when (destination) {
            ImageToVideoDestination.Templates -> {
                TemplateBrowserScreen(
                    selectedTab = selectedTab,
                    currentTemplate = currentTemplate,
                    isPlaying = isPlaying,
                    selectedNavigation = selectedNavigation,
                    onTabSelect = {
                        selectedTab = it
                        currentTemplate = 1
                        templateEdgeHintRes = null
                    },
                    onPlayToggle = { isPlaying = !isPlaying },
                    onMoveTemplate = ::moveTemplate,
                    onTemplatePageSelected = { page ->
                        currentTemplate = page
                        templateEdgeHintRes = null
                    },
                    onUseTemplate = { template ->
                        selectedGenerationTemplate = template
                        destination = ImageToVideoDestination.UploadPhoto
                    },
                    onConversationLog = ::openMyTasks,
                    onFeedback = { destination = ImageToVideoDestination.Feedback },
                    onEditName = { destination = ImageToVideoDestination.EditName },
                    onEditAvatar = ::openAvatarPicker,
                    isAvatarSaving = profileEditingController.avatarSaving,
                    onTemplateRetry = ::retryTemplates,
                    onNavigationSelect = ::navigateFromBottomNavigation,
                    selectedProductId = topUpPaymentController.selectedProductId,
                    topUpState = topUpState,
                    purchaseState = topUpPaymentController.state,
                    checkoutState = topUpPaymentController.checkoutState,
                    isReconciling = topUpPaymentController.reconciliationCount > 0,
                    onProductSelect = topUpPaymentController::selectProduct,
                    onPreparePayment = topUpPaymentController::prepare,
                    onTopUpRetry = { topUpRefreshSerial += 1 },
                    edgeHint = templateEdgeHint,
                    onEdgeHintDismiss = { messageId ->
                        if (templateEdgeHintSerial.toLong() == messageId) {
                            templateEdgeHintRes = null
                        }
                    },
                    backendState = backendState,
                )
            }

            ImageToVideoDestination.UploadPhoto -> {
                UploadPhotoScreen(
                    selectedNavigation = selectedNavigation,
                    onBack = { destination = ImageToVideoDestination.Templates },
                    onNavigationSelect = ::navigateFromBottomNavigation,
                    diamondBalance = backendState.diamondBalance,
                    selectedTemplate = selectedGenerationTemplate,
                    trackedTasks = sessionTasks,
                    onTaskCreated = ::handleGenerationTaskCreated,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ImageToVideoDestination.MyTasks -> {
                val resultTask = selectedResultTask
                if (resultTask != null) {
                    GenerationResultScreen(
                        task = resultTask,
                        onBack = { selectedResultTask = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MyTasksScreen(
                        tasks = trackedTasks,
                        isLoading = isLoadingTaskHistory,
                        errorMessage = taskHistoryError,
                        onBack = { destination = ImageToVideoDestination.Templates },
                        onOpenTask = { selectedResultTask = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            ImageToVideoDestination.Feedback -> {
                FeedbackScreen(
                    onBack = { destination = ImageToVideoDestination.Templates },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ImageToVideoDestination.EditName -> {
                BackHandler {
                    if (!profileEditingController.nameSaving) {
                        profileEditingController.clearNameError()
                        destination = ImageToVideoDestination.Templates
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                        MeScreen(
                            selectedNavigation = 3,
                            onConversationLog = ::openMyTasks,
                            onFeedback = { destination = ImageToVideoDestination.Feedback },
                            onEditName = {},
                            onEditAvatar = ::openAvatarPicker,
                            isAvatarSaving = profileEditingController.avatarSaving,
                            profileName = backendState.profileName,
                            profileId = backendState.profileId,
                            profileAvatarUrl = backendState.profileAvatarUrl,
                            diamondBalance = backendState.diamondBalance,
                            onNavigationSelect = ::navigateFromBottomNavigation,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.68f))
                            .semantics {
                                contentDescription = context.getString(R.string.dismiss_name_editor_description)
                            }
                            .clickable(enabled = !profileEditingController.nameSaving) {
                                profileEditingController.clearNameError()
                                destination = ImageToVideoDestination.Templates
                            },
                    )
                    NameEditorSheet(
                        currentName = backendState.profileName,
                        isSaving = profileEditingController.nameSaving,
                        errorMessage = profileEditingController.nameError,
                        onInputChanged = profileEditingController::clearNameError,
                        onClose = {
                            if (!profileEditingController.nameSaving) {
                                profileEditingController.clearNameError()
                                destination = ImageToVideoDestination.Templates
                            }
                        },
                        onSave = profileEditingController::saveName,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        TransientMessageHost(
            message = profileMessage,
            onDismiss = { messageId ->
                if (profileMessage?.id == messageId) profileMessage = null
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 82.dp),
        )
        val thirdPartyRoute = topUpPaymentController.state as? TopUpPurchaseState.ThirdPartyReady
        if (thirdPartyRoute != null && thirdPartyRoute.openMode == "webview" &&
            topUpPaymentController.checkoutState is TopUpCheckoutState.AwaitingPayment
        ) {
            ThirdPartyCheckoutOverlay(
                route = thirdPartyRoute,
                onLoaded = { topUpPaymentController.onThirdPartyPageLoaded(thirdPartyRoute) },
                onError = { topUpPaymentController.onThirdPartyPageError(thirdPartyRoute, it) },
                onClose = { topUpPaymentController.closeThirdParty(thirdPartyRoute) },
            )
        }
    }
}

@Composable
private fun TemplateBrowserScreen(
    selectedTab: Int,
    currentTemplate: Int,
    isPlaying: Boolean,
    selectedNavigation: Int,
    selectedProductId: String?,
    topUpState: TopUpUiState,
    purchaseState: TopUpPurchaseState,
    checkoutState: TopUpCheckoutState,
    isReconciling: Boolean,
    onTabSelect: (Int) -> Unit,
    onPlayToggle: () -> Unit,
    onMoveTemplate: (TemplateFeedDirection, Int) -> Unit,
    onTemplatePageSelected: (Int) -> Unit,
    onUseTemplate: (SelectedGenerationTemplate?) -> Unit,
    onConversationLog: () -> Unit,
    onFeedback: () -> Unit,
    onEditName: () -> Unit,
    onEditAvatar: () -> Unit,
    isAvatarSaving: Boolean,
    onTemplateRetry: (TemplateSection) -> Unit,
    onNavigationSelect: (Int) -> Unit,
    onProductSelect: (String) -> Unit,
    onPreparePayment: () -> Unit,
    onTopUpRetry: () -> Unit,
    edgeHint: TransientMessage?,
    onEdgeHintDismiss: (Long) -> Unit,
    backendState: AchatBackendUiState,
) {
    if (selectedNavigation == 2) {
        TopUpScreen(
            selectedNavigation = selectedNavigation,
            selectedProductId = selectedProductId,
            state = topUpState,
            diamondBalance = backendState.diamondBalance,
            purchaseState = purchaseState,
            checkoutState = checkoutState,
            isReconciling = isReconciling,
            onProductSelect = onProductSelect,
            onPreparePayment = onPreparePayment,
            onRetry = onTopUpRetry,
            onNavigationSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
        return
    }

    if (selectedNavigation == 3) {
        MeScreen(
            selectedNavigation = selectedNavigation,
            onConversationLog = onConversationLog,
            onFeedback = onFeedback,
            onEditName = onEditName,
            onEditAvatar = onEditAvatar,
            isAvatarSaving = isAvatarSaving,
            profileName = backendState.profileName,
            profileId = backendState.profileId,
            profileAvatarUrl = backendState.profileAvatarUrl,
            diamondBalance = backendState.diamondBalance,
            onNavigationSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
        return
    }

    val section = if (selectedNavigation == 1) TemplateSection.Image else TemplateSection.Video
    val templates = if (section == TemplateSection.Image) backendState.imageTemplates else backendState.videoTemplates
    val templatesLoading = if (section == TemplateSection.Image) {
        backendState.imageTemplatesLoading
    } else {
        backendState.videoTemplatesLoading
    }
    val templateErrorMessage = if (section == TemplateSection.Image) {
        backendState.imageTemplateErrorMessage
    } else {
        backendState.videoTemplateErrorMessage
    }
    val selectedTemplateIndex = if (templates.isEmpty()) 0 else (currentTemplate - 1) % templates.size
    val selectedTemplate = templates.getOrNull(selectedTemplateIndex)
    val navigationTotal = templates.size
    val visiblePrice = visibleTemplatePrice(selectedTemplate)
    val nearbyVideoUrls = if (section == TemplateSection.Video) {
        templates.nearbyVideoPreviewUrls(selectedTemplateIndex)
    } else {
        emptyList()
    }
    val nearbyImageUrls = templates.nearbyImagePreviewUrls(selectedTemplateIndex)
    val selectedGenerationTemplate = selectedTemplate?.let { template ->
        SelectedGenerationTemplate(
            templateId = template.id,
            modality = if (section == TemplateSection.Image) "image" else "video",
            quality = if (template.fastPrice != null) "fast" else "quality",
            title = template.name,
            previewMedia = template.toPreviewMedia(),
            durationSeconds = template.durationSeconds.takeIf { it > 0 } ?: 5,
        )
    }
    TemplateMediaPreloader(videoUrls = nearbyVideoUrls, imageUrls = nearbyImageUrls)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Header(section = section, diamondBalance = backendState.diamondBalance)
        Spacer(Modifier.height(8.dp))
        CategoryTabs(
            section = section,
            selectedTab = selectedTab,
            onSelect = onTabSelect,
        )
        TemplateBackendStatus(
            isLoading = templatesLoading,
            errorMessage = templateErrorMessage,
            selectedTemplate = selectedTemplate,
        )
        Spacer(Modifier.height(7.dp))
        TemplateFeedPager(
            templates = templates,
            currentTemplate = currentTemplate,
            isLoading = templatesLoading,
            errorMessage = templateErrorMessage,
            showLocalFallback = shouldShowLocalTemplateFallback(
                templates = templates,
                isLoading = templatesLoading,
                errorMessage = templateErrorMessage,
            ),
            onRetry = { onTemplateRetry(section) },
            isPlaying = isPlaying,
            onPlayToggle = onPlayToggle,
            onMoveTemplate = { direction -> onMoveTemplate(direction, navigationTotal) },
            onTemplatePageSelected = onTemplatePageSelected,
            edgeHint = edgeHint,
            onEdgeHintDismiss = onEdgeHintDismiss,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        TemplateButton(
            price = visiblePrice,
            enabled = selectedGenerationTemplate != null,
            onClick = { onUseTemplate(selectedGenerationTemplate) },
        )
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
    }
}

@Composable
private fun TemplateFeedPager(
    templates: List<VisualTemplate>,
    currentTemplate: Int,
    isLoading: Boolean,
    errorMessage: String?,
    showLocalFallback: Boolean,
    onRetry: () -> Unit,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onMoveTemplate: (TemplateFeedDirection) -> Unit,
    onTemplatePageSelected: (Int) -> Unit,
    edgeHint: TransientMessage?,
    onEdgeHintDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (templates.isEmpty()) {
        if (showLocalFallback) {
            HeroCard(
                currentPage = 1,
                totalPages = 1,
                durationSeconds = 5,
                previewMedia = TemplatePreviewMedia.LocalPlaceholder,
                isPlaying = isPlaying,
                onPlayToggle = onPlayToggle,
                onPrevious = { onMoveTemplate(TemplateFeedDirection.Previous) },
                onNext = { onMoveTemplate(TemplateFeedDirection.Next) },
                enableSwipeGestures = false,
                edgeHint = edgeHint,
                onEdgeHintDismiss = onEdgeHintDismiss,
                modifier = modifier,
            )
            return
        }
        LiveTemplatePlaceholderCard(
            isLoading = isLoading,
            errorMessage = errorMessage,
            onRetry = onRetry,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val initialPage = (currentTemplate - 1).coerceIn(0, templates.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage) { templates.size }

    LaunchedEffect(currentTemplate, templates.size) {
        val targetPage = (currentTemplate - 1).coerceIn(0, templates.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState, templates.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onTemplatePageSelected(page + 1)
        }
    }

    VerticalPager(
        state = pagerState,
        key = { page -> templatePagerKey(templates, page) },
        modifier = modifier,
    ) { page ->
        val template = templates[page]
        HeroCard(
            currentPage = page + 1,
            totalPages = templates.size,
            durationSeconds = template.durationSeconds.takeIf { it > 0 } ?: 5,
            previewMedia = template.toPreviewMedia(),
            isPlaying = shouldPlayTemplatePage(
                page = page,
                currentPage = pagerState.currentPage,
                requestedPlaying = isPlaying,
            ),
            onPlayToggle = onPlayToggle,
            onPrevious = {
                if (page == 0) {
                    onMoveTemplate(TemplateFeedDirection.Previous)
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(page - 1)
                    }
                }
            },
            onNext = {
                if (page == templates.lastIndex) {
                    onMoveTemplate(TemplateFeedDirection.Next)
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(page + 1)
                    }
                }
            },
            enableSwipeGestures = false,
            edgeHint = edgeHint,
            onEdgeHintDismiss = onEdgeHintDismiss,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun LiveTemplatePlaceholderCard(
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        when {
            isLoading -> R.string.template_feed_loading_title
            errorMessage != null -> R.string.template_feed_error_title
            else -> R.string.template_feed_empty_title
        },
    )
    val body = when {
        isLoading -> stringResource(R.string.template_feed_loading_body)
        errorMessage != null -> errorMessage
        else -> stringResource(R.string.template_feed_empty_body)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xE6080A13))
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.45f), AchatPink.copy(alpha = 0.45f))),
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(AchatCyan.copy(alpha = 0.12f))
                    .border(1.dp, AchatCyan.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DiamondIcon(16.dp)
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                color = AchatMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (!isLoading) {
                Text(
                    text = stringResource(R.string.template_feed_retry),
                    color = AchatCyan,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, AchatCyan.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun TemplateMediaPreloader(
    videoUrls: List<String>,
    imageUrls: List<String>,
    coordinator: TemplateMediaPrefetchCoordinator = DefaultTemplateMediaPrefetchCoordinator,
) {
    val context = LocalContext.current
    DisposableEffect(videoUrls, imageUrls, coordinator) {
        val videoHandle = coordinator.prefetchVideoPrefixes(context, videoUrls)
        val imageHandle = coordinator.prefetchImages(context, imageUrls)
        onDispose {
            videoHandle.cancel()
            imageHandle.cancel()
        }
    }
}

@Composable
private fun MeScreen(
    selectedNavigation: Int,
    onConversationLog: () -> Unit,
    onFeedback: () -> Unit,
    onEditName: () -> Unit,
    onEditAvatar: () -> Unit,
    isAvatarSaving: Boolean,
    profileName: String,
    profileId: String,
    profileAvatarUrl: String?,
    diamondBalance: Int,
    onNavigationSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val copyIdLabel = stringResource(R.string.profile_copy_id_description)
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        MeHeader(diamondBalance = diamondBalance)
        Spacer(Modifier.height(28.dp))
        ProfileCard(
            profileName = profileName,
            profileId = profileId,
            avatarUrl = profileAvatarUrl,
            onEditName = onEditName,
            onEditAvatar = onEditAvatar,
            onCopyId = {
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(copyIdLabel, profileId),
                )
            },
            nameEditEnabled = !isAvatarSaving,
            avatarEditEnabled = !isAvatarSaving,
        )
        if (isAvatarSaving) {
            Text(
                text = stringResource(R.string.profile_avatar_updating),
                color = AchatCyan,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiamondIcon(7.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.system_modules),
                color = AchatCyan,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModuleRow(
                icon = ModuleIcon.ConversationLog,
                title = stringResource(R.string.conversation_log),
                subtitle = stringResource(R.string.conversation_log_subtitle),
                trailing = stringResource(R.string.module_count, 108),
                onClick = onConversationLog,
            )
            ModuleRow(
                icon = ModuleIcon.Feedback,
                title = stringResource(R.string.feedback),
                subtitle = stringResource(R.string.feedback_subtitle),
                trailing = stringResource(R.string.module_new),
                onClick = onFeedback,
            )
            ModuleRow(
                icon = ModuleIcon.Edit,
                title = stringResource(R.string.edit_name),
                subtitle = stringResource(R.string.edit_name_subtitle),
                onClick = { if (!isAvatarSaving) onEditName() },
            )
        }
        Spacer(Modifier.weight(1f))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun MeHeader(diamondBalance: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.me_title),
            color = AchatCyan,
            style = MaterialTheme.typography.headlineMedium,
        )
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(12.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = diamondBalance.toString(),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun ProfileCard(
    profileName: String,
    profileId: String,
    avatarUrl: String?,
    onEditName: () -> Unit,
    onEditAvatar: () -> Unit,
    onCopyId: () -> Unit,
    nameEditEnabled: Boolean = true,
    avatarEditEnabled: Boolean = true,
) {
    val avatarDescription = stringResource(R.string.profile_avatar_description)
    val avatarEditDescription = stringResource(R.string.profile_avatar_edit_description)
    val editDescription = stringResource(R.string.profile_edit_description)
    val copyIdDescription = stringResource(R.string.profile_copy_id_description)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .offset(y = 20.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xF0181B28), Color(0xF20B0C14))))
                .border(1.dp, Color.White.copy(alpha = 0.05f))
                .padding(start = 102.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = profileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .height(24.dp)
                            .widthIn(max = 132.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xC50B0E18))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                            .clickable(onClick = onCopyId)
                            .semantics { contentDescription = copyIdDescription }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.profile_id_format, profileId),
                            color = AchatMuted,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(5.dp))
                        CopyGlyph(tint = AchatMuted, modifier = Modifier.size(11.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable(enabled = nameEditEnabled, onClick = onEditName)
                            .semantics { contentDescription = editDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(CutCornerShape(topEnd = 7.dp, bottomStart = 7.dp))
                                .background(Color(0x6612D8EB))
                                .border(
                                    1.dp,
                                    AchatCyan.copy(alpha = 0.72f),
                                    CutCornerShape(topEnd = 7.dp, bottomStart = 7.dp),
                                )
                                .padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PencilGlyph(tint = AchatCyan, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = stringResource(R.string.profile_edit),
                                color = AchatCyan,
                                style = MaterialTheme.typography.labelMedium,
                                letterSpacing = 1.5.sp,
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(90.dp)
                .offset(y = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AchatCyan, AchatPink)))
                    .padding(3.dp),
            ) {
                AsyncImage(
                    model = avatarUrl ?: R.drawable.hero_portrait,
                    contentDescription = avatarDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp)
                    .clickable(enabled = avatarEditEnabled, onClick = onEditAvatar)
                    .semantics { contentDescription = avatarEditDescription },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(29.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF07101A))
                        .border(1.dp, AchatCyan, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PencilGlyph(tint = AchatCyan, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun PencilGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawFigmaPencil(tint)
    }
}

private fun DrawScope.drawFigmaPencil(tint: Color) {
    val strokeWidth = 1.45.dp.toPx()
    val outline = Path().apply {
        moveTo(size.width * 0.16f, size.height * 0.86f)
        lineTo(size.width * 0.23f, size.height * 0.65f)
        lineTo(size.width * 0.65f, size.height * 0.23f)
        lineTo(size.width * 0.8f, size.height * 0.38f)
        lineTo(size.width * 0.38f, size.height * 0.8f)
        close()
    }
    drawPath(
        path = outline,
        color = tint,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawLine(
        color = tint,
        start = Offset(size.width * 0.58f, size.height * 0.3f),
        end = Offset(size.width * 0.73f, size.height * 0.45f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(size.width * 0.23f, size.height * 0.65f),
        end = Offset(size.width * 0.38f, size.height * 0.8f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

@Composable
private fun CopyGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.2.dp.toPx())
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.26f, size.height * 0.08f),
            size = Size(size.width * 0.62f, size.height * 0.68f),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.08f, size.height * 0.26f),
            size = Size(size.width * 0.62f, size.height * 0.66f),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
            style = stroke,
        )
    }
}

internal enum class ModuleIcon { ConversationLog, Feedback, Edit }

@Composable
private fun ModuleRow(
    icon: ModuleIcon,
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xBA090D19))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleGlyph(icon = icon)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (trailing != null) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = trailing,
                        color = AchatPink,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = AchatMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("›", color = AchatMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ModuleGlyph(icon: ModuleIcon) {
    val tint = when (icon) {
        ModuleIcon.ConversationLog -> AchatCyan
        ModuleIcon.Feedback -> AchatPink
        ModuleIcon.Edit -> Color(0xFF7D55E9)
    }
    val description = stringResource(
        when (icon) {
            ModuleIcon.ConversationLog -> R.string.conversation_log_icon_description
            ModuleIcon.Feedback -> R.string.feedback_icon_description
            ModuleIcon.Edit -> R.string.edit_name_icon_description
        },
    )
    Canvas(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.1f))
            .border(1.dp, tint.copy(alpha = 0.38f), RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .padding(7.dp),
    ) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            ModuleIcon.ConversationLog -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.08f),
                    size = Size(size.width * 0.68f, size.height * 0.82f),
                    cornerRadius = CornerRadius(1.2.dp.toPx()),
                    style = stroke,
                )
                listOf(0.32f, 0.49f, 0.66f).forEach { y ->
                    drawLine(
                        color = tint,
                        start = Offset(size.width * 0.3f, size.height * y),
                        end = Offset(size.width * 0.7f, size.height * y),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            ModuleIcon.Feedback -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.16f),
                    size = Size(size.width * 0.76f, size.height * 0.68f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.13f, size.height * 0.58f),
                    end = Offset(size.width * 0.34f, size.height * 0.7f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.34f, size.height * 0.7f),
                    end = Offset(size.width * 0.66f, size.height * 0.7f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.66f, size.height * 0.7f),
                    end = Offset(size.width * 0.87f, size.height * 0.58f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            ModuleIcon.Edit -> {
                drawFigmaPencil(tint)
            }
        }
    }
}

@Composable
internal fun MyTasksScreen(
    tasks: List<TrackedGenerationTask>,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onOpenTask: (TrackedGenerationTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SecondaryHeader(
            title = stringResource(R.string.my_tasks_title),
            backDescription = stringResource(R.string.back_to_me_description),
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        if (isLoading) {
            Text(
                text = stringResource(R.string.task_history_loading),
                color = AchatCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = AchatPink,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (tasks.isEmpty() && !isLoading && errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                EmptyTasksCard(modifier = Modifier.padding(top = 142.dp))
            }
        } else if (tasks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                tasks.forEach { task ->
                    TaskStatusCard(task = task, onOpen = { onOpenTask(task) })
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TaskStatusCard(task: TrackedGenerationTask, onOpen: () -> Unit) {
    val borderBrush = Brush.linearGradient(
        listOf(
            AchatCyan.copy(alpha = if (task.canOpenResult) 0.78f else 0.28f),
            AchatPink.copy(alpha = if (task.status == "failed") 0.84f else 0.34f),
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xBB090D1B))
            .border(1.dp, borderBrush, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101626))
                .border(1.dp, AchatCyan.copy(alpha = 0.42f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DiamondIcon(15.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.task_card_status, task.taskId.take(8), task.status),
                color = AchatMuted,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
            )
            if (!task.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = task.errorMessage,
                    color = AchatPink.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (task.canOpenResult) AchatCyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                .border(
                    1.dp,
                    if (task.canOpenResult) AchatCyan.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(14.dp),
                )
                .clickable(enabled = task.canOpenResult, onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (task.canOpenResult) {
                    stringResource(R.string.task_open_result)
                } else {
                    task.status.uppercase()
                },
                color = if (task.canOpenResult) Color.White else AchatMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun GenerationResultScreen(
    task: TrackedGenerationTask,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SecondaryHeader(
            title = stringResource(R.string.task_result_title),
            backDescription = stringResource(R.string.back_to_me_description),
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xBB080A14))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.7f), AchatPink.copy(alpha = 0.7f))),
                    RoundedCornerShape(10.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                text = task.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.task_card_status, task.taskId.take(8), task.status),
                color = AchatMuted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (task.canPreviewAsImage) {
                    AsyncImage(
                        model = task.resultUrl,
                        contentDescription = stringResource(R.string.task_result_image_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.task_result_preview_unavailable),
                        color = AchatMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x78101524))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackGlyph(
            modifier = Modifier
                .size(26.dp)
                .semantics { contentDescription = backDescription }
                .clickable(onClick = onBack),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun EmptyTasksCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 330.dp)
            .height(260.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xC0161730), Color(0xF0080913)),
                    radius = 510f,
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatPink.copy(alpha = 0.72f), AchatCyan.copy(alpha = 0.64f))),
                RoundedCornerShape(4.dp),
            ),
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val corner = 21.dp.toPx()
            val accentStroke = 2.dp.toPx()
            drawLine(AchatPink, Offset.Zero, Offset(corner, 0f), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatPink, Offset.Zero, Offset(0f, corner), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width - corner, 0f), Offset(size.width, 0f), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width, 0f), Offset(size.width, corner), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatPink, Offset(0f, size.height - corner), Offset(0f, size.height), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatPink, Offset(0f, size.height), Offset(corner, size.height), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatPink, Offset(size.width, size.height - corner), Offset(size.width, size.height), strokeWidth = accentStroke, cap = StrokeCap.Round)
            drawLine(AchatPink, Offset(size.width - corner, size.height), Offset(size.width, size.height), strokeWidth = accentStroke, cap = StrokeCap.Round)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(AchatPink))
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF8E33D7)))
            Box(Modifier.size(6.dp).clip(CircleShape).background(AchatCyan.copy(alpha = 0.52f)))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 19.dp, top = 31.dp)
                .width(30.dp)
                .height(1.dp)
                .background(AchatCyan.copy(alpha = 0.28f)),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xD0101024))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(AchatPink.copy(alpha = 0.72f), AchatCyan.copy(alpha = 0.5f))),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(31.dp)) {
                val outer = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(0f, size.height / 2f)
                    close()
                }
                val inset = size.width * 0.22f
                val inner = Path().apply {
                    moveTo(size.width / 2f, inset)
                    lineTo(size.width - inset, size.height / 2f)
                    lineTo(size.width / 2f, size.height - inset)
                    lineTo(inset, size.height / 2f)
                    close()
                }
                drawPath(outer, color = AchatPink.copy(alpha = 0.82f), style = Stroke(1.2.dp.toPx()))
                drawPath(inner, color = AchatCyan.copy(alpha = 0.78f), style = Stroke(1.dp.toPx()))
            }
        }

        Text(
            text = stringResource(R.string.no_tasks_empty),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 149.dp),
        )

        Text(
            text = stringResource(R.string.empty_tasks_diagnostics),
            color = Color.White.copy(alpha = 0.14f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 28.dp),
        )

        Canvas(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 26.dp)
                .size(width = 20.dp, height = 14.dp),
        ) {
            drawLine(
                color = AchatPink.copy(alpha = 0.72f),
                start = Offset(size.width * 0.2f, size.height),
                end = Offset(size.width * 0.38f, 0f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = AchatCyan.copy(alpha = 0.58f),
                start = Offset(size.width * 0.62f, 0f),
                end = Offset(size.width * 0.8f, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun FeedbackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SecondaryHeader(
            title = stringResource(R.string.feedback),
            backDescription = stringResource(R.string.back_to_me_description),
            onBack = onBack,
        )
        Spacer(Modifier.height(30.dp))
        FeedbackPromptCard()
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackMediaButton(
                label = stringResource(R.string.take_photo),
                primary = false,
                modifier = Modifier.weight(1f),
            )
            FeedbackMediaButton(
                label = stringResource(R.string.library),
                primary = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        FeedbackInputBox()
        Spacer(Modifier.weight(1f))
        FeedbackSubmitButton()
    }
}

@Composable
private fun FeedbackPromptCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.radialGradient(listOf(Color(0xB4161830), Color(0xEA090A13))))
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatPink.copy(alpha = 0.75f), AchatCyan.copy(alpha = 0.55f))),
                RoundedCornerShape(4.dp),
            )
            .padding(14.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val corner = 24.dp.toPx()
            drawLine(AchatPink, Offset.Zero, Offset(corner, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatPink, Offset.Zero, Offset(0f, corner), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width - corner, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width, size.height - corner), Offset(size.width, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width - corner, size.height), Offset(size.width, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)) {
            Text(
                text = stringResource(R.string.feedback_prompt_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.feedback_prompt_body),
                color = AchatMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun FeedbackMediaButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (primary) {
        Brush.horizontalGradient(listOf(Color(0xFF7D55E9), AchatPink))
    } else {
        Brush.horizontalGradient(listOf(Color(0xCF0B1422), Color(0xCF090C17)))
    }
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                1.dp,
                if (primary) Color.White.copy(alpha = 0.12f) else AchatCyan.copy(alpha = 0.45f),
                RoundedCornerShape(6.dp),
            )
            .clickable { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        DiamondIcon(9.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FeedbackInputBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xD40B1020))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.feedback_input_placeholder),
            color = AchatMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FeedbackSubmitButton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2FDDF3), Color(0xFF7952E8), AchatPink)))
            .clickable { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.submit_feedback),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text("✦", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun NameEditorSheet(
    currentName: String,
    isSaving: Boolean,
    errorMessage: String?,
    onInputChanged: () -> Unit = {},
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    imeInsets: WindowInsets = WindowInsets.ime,
    modifier: Modifier = Modifier,
) {
    val closeDescription = stringResource(R.string.close_name_picker_description)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val localView = LocalView.current
    val activity = LocalActivity.current
    val inputMethodManager = remember(localView) {
        localView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    var name by rememberSaveable { mutableStateOf(currentName) }
    val normalizedName = name.trim()
    val validationMessage = if (normalizedName.length in 2..50) {
        null
    } else {
        stringResource(R.string.profile_name_validation)
    }
    val canSave = !isSaving && validationMessage == null && normalizedName != currentName.trim()
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        repeat(5) {
            delay(60)
            if (localView.hasWindowFocus()) {
                keyboardController?.show()
                showSoftwareKeyboard(inputMethodManager, localView)
                activity?.window?.let { window ->
                    WindowCompat.getInsetsController(window, localView).show(WindowInsetsCompat.Type.ime())
                }
                return@LaunchedEffect
            }
        }
        keyboardController?.show()
        showSoftwareKeyboard(inputMethodManager, localView)
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, localView).show(WindowInsetsCompat.Type.ime())
        }
    }
    DisposableEffect(activity) {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        onDispose {
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(imeInsets)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF31A1130), Color(0xFF070812))))
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.28f), AchatPink.copy(alpha = 0.34f))),
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 48.dp)
                .semantics { contentDescription = closeDescription }
                .clickable(enabled = !isSaving) {
                    keyboardController?.hide()
                    onClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(38.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.7f)),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.name_picker_selected),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = {
                if (it.length <= 50) {
                    name = it
                    onInputChanged()
                }
            },
            label = { Text(stringResource(R.string.profile_name_label)) },
            singleLine = true,
            enabled = !isSaving,
            isError = validationMessage != null || !errorMessage.isNullOrBlank(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (canSave) {
                        keyboardController?.hide()
                        onSave(normalizedName)
                    }
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        val visibleError = errorMessage ?: validationMessage
        if (!visibleError.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = visibleError,
                color = AchatPink,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (canSave) {
                    Brush.verticalGradient(listOf(Color(0xFF872CF0), Color(0xFF45156D)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xD5131A2A), Color(0xD80B0F1D)))
                },
            )
            .border(
                1.dp,
                if (canSave) AchatPink.copy(alpha = 0.48f) else AchatCyan.copy(alpha = 0.24f),
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = canSave) {
                keyboardController?.hide()
                onSave(normalizedName)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (isSaving) R.string.profile_name_saving else R.string.profile_name_save,
                ),
                color = if (canSave || isSaving) Color.White else AchatMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Suppress("DEPRECATION")
private fun showSoftwareKeyboard(inputMethodManager: InputMethodManager, view: android.view.View) {
    inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_FORCED)
}

@Composable
internal fun TopUpScreen(
    selectedNavigation: Int,
    selectedProductId: String?,
    state: TopUpUiState,
    diamondBalance: Int = state.diamondBalance,
    purchaseState: TopUpPurchaseState,
    checkoutState: TopUpCheckoutState = TopUpCheckoutState.Idle,
    isReconciling: Boolean = false,
    onProductSelect: (String) -> Unit,
    onPreparePayment: () -> Unit,
    onRetry: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
) {
    val catalog = state.catalog
    val packs = catalog?.products.orEmpty().mapIndexed { index, product ->
        product.toCreditPackPresentation(catalog?.userInfo, index)
    }
    val selectedPack = packs.firstOrNull { it.id == selectedProductId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TopUpHeader(diamondBalance = diamondBalance)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiamondIcon(8.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.choose_pack),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(AchatPink.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.isLoading -> TopUpStatusMessage(stringResource(R.string.top_up_loading))
                !state.errorMessage.isNullOrBlank() -> TopUpErrorState(state.errorMessage, onRetry)
                packs.isEmpty() -> TopUpStatusMessage(stringResource(R.string.top_up_empty))
                else -> {
                    packs.forEach { pack ->
                        val selected = pack.id == selectedProductId
                        CreditPackCard(
                            pack = pack,
                            selected = selected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(creditPackHeightDp(selected).dp),
                            onSelect = { onProductSelect(pack.id) },
                        )
                    }
                    selectedPack?.let { pack ->
                        Spacer(Modifier.height(1.dp))
                        Row(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DiamondIcon(7.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.credit_pack_summary,
                                    pack.credits + (pack.bonus ?: 0),
                                ),
                                color = AchatMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        TopUpPurchaseStatus(purchaseState, checkoutState)
                        if (isReconciling) {
                            TopUpInlineStatus(stringResource(R.string.top_up_payment_waiting), AchatCyan)
                        }
                        Spacer(Modifier.height(8.dp))
                        val isPreparing = purchaseState is TopUpPurchaseState.Preparing
                        val terminalCheckout = checkoutState is TopUpCheckoutState.Failed ||
                            checkoutState == TopUpCheckoutState.Cancelled ||
                            checkoutState == TopUpCheckoutState.TimedOut ||
                            checkoutState is TopUpCheckoutState.Succeeded
                        val isReady = (purchaseState is TopUpPurchaseState.OfficialReady ||
                            purchaseState is TopUpPurchaseState.ThirdPartyReady) && !terminalCheckout
                        StartChatButton(
                            label = when {
                                isPreparing || isReconciling -> stringResource(R.string.top_up_preparing)
                                isReady -> stringResource(R.string.top_up_ready)
                                purchaseState is TopUpPurchaseState.Error || terminalCheckout ->
                                    stringResource(R.string.top_up_try_again)
                                else -> stringResource(R.string.top_up_continue)
                            },
                            enabled = !isPreparing && !isReady && !isReconciling,
                            onClick = onPreparePayment,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun TopUpPurchaseStatus(state: TopUpPurchaseState, checkoutState: TopUpCheckoutState) {
    when (checkoutState) {
        TopUpCheckoutState.Idle -> Unit
        TopUpCheckoutState.Launching -> TopUpInlineStatus(stringResource(R.string.top_up_preparing), AchatCyan)
        TopUpCheckoutState.Closing -> TopUpInlineStatus(stringResource(R.string.top_up_payment_waiting), AchatCyan)
        is TopUpCheckoutState.AwaitingPayment -> TopUpInlineStatus(
            if (checkoutState.pendingStorePurchase) stringResource(R.string.top_up_payment_pending)
            else stringResource(R.string.top_up_payment_waiting),
            AchatCyan,
        )
        is TopUpCheckoutState.Succeeded -> TopUpInlineStatus(stringResource(R.string.top_up_payment_success), AchatCyan)
        is TopUpCheckoutState.Failed -> TopUpInlineStatus(checkoutState.message, AchatPink)
        TopUpCheckoutState.Cancelled -> TopUpInlineStatus(stringResource(R.string.top_up_payment_cancelled), AchatMuted)
        TopUpCheckoutState.TimedOut -> TopUpInlineStatus(stringResource(R.string.top_up_payment_timeout), AchatPink)
    }
    if (checkoutState != TopUpCheckoutState.Idle) return
    when (state) {
        TopUpPurchaseState.Idle,
        is TopUpPurchaseState.Preparing,
        -> Unit
        is TopUpPurchaseState.Error -> Text(
            text = state.message,
            color = AchatPink,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        is TopUpPurchaseState.OfficialReady -> PaymentRouteReadyCard(
            title = stringResource(R.string.top_up_official_ready),
            channelCode = state.channelCode,
        )
        is TopUpPurchaseState.ThirdPartyReady -> PaymentRouteReadyCard(
            title = stringResource(R.string.top_up_third_party_ready),
            channelCode = state.channelCode,
        )
    }
}

@Composable
private fun TopUpInlineStatus(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThirdPartyCheckoutOverlay(
    route: TopUpPurchaseState.ThirdPartyReady,
    onLoaded: () -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070812))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(route.channelCode, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.top_up_close_checkout),
                color = AchatCyan,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url ?: return true
                            if (target.scheme != "https") {
                                onError("Blocked insecure checkout navigation")
                                return true
                            }
                            return false
                        }
                        override fun onPageFinished(view: WebView?, url: String?) = onLoaded()
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) = onError(error?.description?.toString().orEmpty())
                    }
                    loadUrl(route.paymentUrl)
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            },
        )
    }
}

@Composable
private fun PaymentRouteReadyCard(title: String, channelCode: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xB20A111C))
            .border(1.dp, AchatCyan.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(title, color = AchatCyan, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(channelCode, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(3.dp))
        Text(
            stringResource(R.string.top_up_phase_three_note),
            color = AchatMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TopUpStatusMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = AchatMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TopUpErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = AchatPink, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.top_up_retry),
            color = AchatCyan,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TopUpHeader(diamondBalance: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = stringResource(R.string.system_credits),
                color = AchatPink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.top_up_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(12.dp)
            Spacer(Modifier.width(6.dp))
            Text(diamondBalance.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CreditPackCard(
    pack: CreditPack,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderBrush = if (selected) {
        Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.95f), AchatPink.copy(alpha = 0.82f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f)))
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.horizontalGradient(
                    if (selected) {
                        listOf(Color(0xEE16244A), Color(0xEE161126))
                    } else {
                        listOf(Color(0xD90C1324), Color(0xD9080A13))
                    },
                ),
            )
            .border(1.dp, borderBrush, RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        if (!selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(pack.accentColor.copy(alpha = 0.84f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (selected) 16.dp else 12.dp,
                    vertical = if (selected) 13.dp else 9.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 44.dp else 30.dp)
                        .clip(RoundedCornerShape(if (selected) 8.dp else 5.dp))
                        .background(pack.accentColor.copy(alpha = 0.08f))
                        .border(
                            1.dp,
                            pack.accentColor.copy(alpha = 0.55f),
                            RoundedCornerShape(if (selected) 8.dp else 5.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconSize = if (selected) 20.dp else 13.dp
                    DiamondOutlineIcon(tint = pack.accentColor, modifier = Modifier.size(iconSize))
                    pack.iconUrl?.let { iconUrl ->
                        AsyncImage(
                            model = iconUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
                Spacer(Modifier.width(if (selected) 12.dp else 9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = pack.credits.toString(),
                            color = Color.White,
                            fontSize = if (selected) 22.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.credit_pack_unit),
                            color = pack.accentColor,
                            fontSize = if (selected) 9.sp else 7.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = pack.description,
                        color = Color(0xFF85899E),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (pack.badgeText != null || pack.bonus != null || pack.badgeTextRes != null) {
                    BonusBadge(pack = pack, selected = selected)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f)),
            )
            Spacer(Modifier.height(if (selected) 9.dp else 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (selected) stringResource(R.string.credit_pack_protocol) else pack.tierLabel,
                    color = AchatMuted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.weight(1f))
                pack.originalPrice?.let { originalPrice ->
                    Text(
                        text = originalPrice,
                        color = AchatMuted,
                        fontSize = 9.sp,
                        textDecoration = TextDecoration.LineThrough,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = pack.price,
                    color = if (selected) AchatCyan else Color.White,
                    fontSize = if (selected) 18.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BonusBadge(pack: CreditPack, selected: Boolean) {
    val label = pack.badgeText
        ?: pack.bonus?.let { stringResource(R.string.credit_pack_bonus, it) }
        ?: pack.badgeTextRes?.let { stringResource(it) }
        ?: return
    Text(
        text = label,
        color = Color.White,
        fontSize = if (selected) 9.sp else 7.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp))
            .background(Brush.horizontalGradient(listOf(AchatPink, Color(0xFF7A52E8))))
            .padding(horizontal = if (selected) 12.dp else 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun DiamondOutlineIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.05f)
            lineTo(size.width * 0.94f, size.height * 0.42f)
            lineTo(size.width * 0.5f, size.height * 0.95f)
            lineTo(size.width * 0.06f, size.height * 0.42f)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun StartChatButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF30DDF3), Color(0xFF7D55E9), AchatPink)
                        .map { if (enabled) it else it.copy(alpha = 0.42f) },
                ),
            )
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text("➜", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UploadPhotoScreen(
    selectedNavigation: Int,
    onBack: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
    diamondBalance: Int,
    selectedTemplate: SelectedGenerationTemplate?,
    trackedTasks: List<TrackedGenerationTask>,
    onTaskCreated: (VisualGenerationTask, TrackedGenerationTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(appContext) { createAchatRepository(appContext) }
    val scope = rememberCoroutineScope()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var uploadedResourceId by remember { mutableStateOf<String?>(null) }
    var currentTask by remember { mutableStateOf<VisualGenerationTask?>(null) }
    var uploadInProgress by remember { mutableStateOf(false) }
    var uploadMessage by remember { mutableStateOf<TransientMessage?>(null) }
    var uploadMessageSerial by remember { mutableIntStateOf(0) }
    val chooseFirstMessage = stringResource(R.string.upload_choose_first)
    val liveTemplateRequired = stringResource(R.string.live_template_required)
    val taskCreatedPattern = stringResource(R.string.task_created)
    val taskStatusPattern = stringResource(R.string.task_status)
    val taskSucceededPattern = stringResource(R.string.task_succeeded)
    val taskFailedPattern = stringResource(R.string.task_failed)
    val uploadFailedMessage = stringResource(R.string.upload_failed)
    val uploadSuccessPattern = stringResource(R.string.upload_success)
    fun showUploadMessage(
        text: String,
        tone: TransientMessageTone = TransientMessageTone.Neutral,
    ) {
        uploadMessageSerial += 1
        uploadMessage = TransientMessage(
            id = uploadMessageSerial.toLong(),
            text = text,
            tone = tone,
        )
    }
    fun uploadSelectedPhoto(uri: Uri) {
        uploadInProgress = true
        uploadMessage = null
        scope.launch {
            runCatching {
                repository.uploadSourceImage(uri)
            }.onSuccess { resource ->
                uploadedResourceId = resource.id
                showUploadMessage(
                    text = uploadSuccessPattern.format(resource.id.take(8)),
                    tone = TransientMessageTone.Success,
                )
            }.onFailure { error ->
                uploadedResourceId = null
                showUploadMessage(
                    text = visualGenerationUserMessage(error.message, uploadFailedMessage),
                    tone = TransientMessageTone.Error,
                )
            }
            uploadInProgress = false
        }
    }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        selectedImageUri = uri
        uploadedResourceId = null
        currentTask = null
        uploadSelectedPhoto(uri)
    }
    fun launchPhotoPicker() {
        if (!uploadInProgress) {
            pickerLauncher.launch(
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    type = "image/*"
                },
            )
        }
    }

    val trackedCurrentTask = currentTask?.taskId?.let { taskId ->
        trackedTasks.firstOrNull { it.taskId == taskId }
    }
    LaunchedEffect(trackedCurrentTask?.taskId, trackedCurrentTask?.status) {
        val task = trackedCurrentTask ?: return@LaunchedEffect
        if (task.isFinished) {
            if (task.status == "succeeded") {
                showUploadMessage(
                    text = taskSucceededPattern.format(task.taskId.take(8)),
                    tone = TransientMessageTone.Success,
                )
            } else {
                showUploadMessage(
                    text = taskFailedPattern.format(task.errorMessage ?: task.status),
                    tone = TransientMessageTone.Error,
                )
            }
            return@LaunchedEffect
        }

        showUploadMessage(taskStatusPattern.format(task.taskId.take(8), task.status))
    }

    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        UploadPhotoHeader(onBack = onBack, diamondBalance = diamondBalance)
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.template_preview_heading),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        TemplatePreviewPanel(
            media = selectedTemplate?.previewMedia ?: TemplatePreviewMedia.LocalPlaceholder,
            durationSeconds = selectedTemplate?.durationSeconds ?: 5,
            isPlaying = selectedTemplate?.previewMedia is TemplatePreviewMedia.RemoteVideo,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.template_upload_heading),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        PhotoUploadPanel(
            selectedImage = selectedImageUri,
            uploadMessage = uploadMessage,
            onMessageDismiss = { messageId ->
                if (uploadMessage?.id == messageId) {
                    uploadMessage = null
                }
            },
            onChoosePhoto = ::launchPhotoPicker,
            enabled = !uploadInProgress,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(10.dp))
        UploadActions(
            uploadInProgress = uploadInProgress,
            canContinue = uploadedResourceId != null,
            onChoosePhoto = ::launchPhotoPicker,
            onContinue = {
                val resourceId = uploadedResourceId
                if (resourceId == null) {
                    showUploadMessage(chooseFirstMessage, TransientMessageTone.Error)
                    return@UploadActions
                }
                val template = selectedTemplate
                if (template == null || template.templateId.isBlank()) {
                    showUploadMessage(liveTemplateRequired, TransientMessageTone.Error)
                    return@UploadActions
                }
                uploadInProgress = true
                uploadMessage = null
                scope.launch {
                    runCatching {
                        repository.createVisualGenerationTask(
                            modality = template.modality,
                            templateId = template.templateId,
                            quality = template.quality,
                            resourceId = resourceId,
                        )
                    }.onSuccess { task ->
                        currentTask = task
                        onTaskCreated(task, task.toTrackedGenerationTask(template.title))
                        showUploadMessage(taskCreatedPattern.format(task.taskId.take(8), task.status))
                    }.onFailure { error ->
                        showUploadMessage(
                            text = visualGenerationUserMessage(error.message, uploadFailedMessage),
                            tone = TransientMessageTone.Error,
                        )
                    }
                    uploadInProgress = false
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun UploadPhotoHeader(onBack: () -> Unit, diamondBalance: Int) {
    val backDescription = stringResource(R.string.back_to_templates_description)
    val balanceDescription = stringResource(R.string.balance_panel_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x78101524))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackGlyph(
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = backDescription }
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.upload_photo_title),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 11.dp)
                .semantics { contentDescription = balanceDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(11.dp)
            Spacer(Modifier.width(6.dp))
            Text(diamondBalance.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BackgroundGlow() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(AchatPink.copy(alpha = 0.16f), Color.Transparent),
                Offset(size.width * 0.08f, size.height * 0.3f),
                size.width * 0.75f,
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.08f, size.height * 0.3f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(AchatCyan.copy(alpha = 0.1f), Color.Transparent),
                Offset(size.width, size.height * 0.08f),
                size.width * 0.65f,
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width, size.height * 0.08f),
        )
    }
}

@Composable
private fun Header(section: TemplateSection, diamondBalance: Int) {
    val title = stringResource(
        if (section == TemplateSection.Image) R.string.image_to_image_title else R.string.image_to_video_title,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = if (section == TemplateSection.Image) 20.sp else 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(12.dp)
            Spacer(Modifier.width(6.dp))
            Text(diamondBalance.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryTabs(
    section: TemplateSection,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    val firstLabel = stringResource(
        if (section == TemplateSection.Image) R.string.tab_single_image else R.string.tab_hot,
    )
    val secondLabel = stringResource(
        if (section == TemplateSection.Image) R.string.tab_multi_image else R.string.tab_new,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CategoryTab(firstLabel, selectedTab == 0) { onSelect(0) }
        CategoryTab(secondLabel, selectedTab == 1) { onSelect(1) }
    }
}

@Composable
private fun TemplateBackendStatus(
    isLoading: Boolean,
    errorMessage: String?,
    selectedTemplate: VisualTemplate?,
) {
    val statusText = when {
        isLoading -> stringResource(R.string.backend_loading_templates)
        errorMessage != null -> stringResource(R.string.backend_templates_offline)
        selectedTemplate != null -> selectedTemplate.name
        else -> stringResource(R.string.backend_templates_placeholder)
    }
    if (statusText.isBlank()) {
        return
    }
    Spacer(Modifier.height(5.dp))
    Text(
        text = statusText,
        color = if (errorMessage != null) AchatPink else AchatMuted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 32.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else AchatMuted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(if (selected) 25.dp else 0.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(AchatCyan, AchatPink)),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Preview(widthDp = 375, heightDp = 812, showBackground = true)
@Composable
private fun ImageToVideoPreview() {
    AchatTheme {
        ImageToVideoScreen()
    }
}
