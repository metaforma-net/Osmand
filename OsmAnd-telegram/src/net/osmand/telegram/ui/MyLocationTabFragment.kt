package net.osmand.telegram.ui

import android.animation.*
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.support.design.widget.AppBarLayout
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.ListPopupWindow
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.*
import net.osmand.telegram.R
import net.osmand.telegram.TelegramApplication
import net.osmand.telegram.helpers.TelegramHelper
import net.osmand.telegram.helpers.TelegramHelper.TelegramListener
import net.osmand.telegram.helpers.TelegramUiHelper
import net.osmand.telegram.utils.AndroidUtils
import org.drinkless.td.libcore.telegram.TdApi
import java.util.concurrent.TimeUnit

private const val SELECTED_CHATS_KEY = "selected_chats"

class MyLocationTabFragment : Fragment(), TelegramListener {

	private var textMarginSmall: Int = 0
	private var textMarginBig: Int = 0
	private var searchBoxHeight: Int = 0
	private var searchBoxSidesMargin: Int = 0

	private var appBarScrollRange: Int = -1

	private val app: TelegramApplication
		get() = activity?.application as TelegramApplication

	private val telegramHelper get() = app.telegramHelper
	private val settings get() = app.settings

	private lateinit var mainView: View
	private lateinit var appBarLayout: AppBarLayout
	private lateinit var userImage: ImageView
	private lateinit var optionsBtn: ImageView
	private lateinit var textContainer: LinearLayout
	private lateinit var title: TextView
	private lateinit var description: TextView
	private lateinit var searchBox: FrameLayout
	private lateinit var recyclerView: RecyclerView
	private lateinit var switch: Switch

	private lateinit var searchBoxBg: GradientDrawable

	private val adapter = MyLocationListAdapter()

	private var appBarCollapsed = false
	private lateinit var appBarOutlineProvider: ViewOutlineProvider

	private val selectedChats = HashSet<Long>()

	private var actionButtonsListener: ActionButtonsListener? = null

	private var hasAnyChatToShareLocation: Boolean = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val activity = activity
		if (activity is ActionButtonsListener) {
			actionButtonsListener = activity
		}

		textMarginSmall = resources.getDimensionPixelSize(R.dimen.content_padding_standard)
		textMarginBig = resources.getDimensionPixelSize(R.dimen.my_location_text_sides_margin)
		searchBoxHeight = resources.getDimensionPixelSize(R.dimen.search_box_height)
		searchBoxSidesMargin = resources.getDimensionPixelSize(R.dimen.content_padding_half)

		savedInstanceState?.apply {
			selectedChats.addAll(getLongArray(SELECTED_CHATS_KEY).toSet())
			if (selectedChats.isNotEmpty()) {
				actionButtonsListener?.switchButtonsVisibility(true)
			}
		}

		mainView = inflater.inflate(R.layout.fragment_my_location_tab, container, false)
		hasAnyChatToShareLocation = settings.hasAnyChatToShareLocation()

		appBarLayout = mainView.findViewById<AppBarLayout>(R.id.app_bar_layout).apply {
			if (Build.VERSION.SDK_INT >= 21) {
				appBarOutlineProvider = outlineProvider
				outlineProvider = null
			}
			addOnOffsetChangedListener { appBar, offset ->
//				if (appBarScrollRange == -1) {
				appBarScrollRange = appBar.totalScrollRange
//				}
				val collapsed = Math.abs(offset) == appBarScrollRange
				if (collapsed != appBarCollapsed) {
					appBarCollapsed = collapsed
					adjustText()
					adjustAppbar()
					optionsBtn.visibility = if (collapsed) View.VISIBLE else View.GONE
				}
			}
		}

		userImage = mainView.findViewById<ImageView>(R.id.my_location_user_image).apply {
			setImageResource(R.drawable.img_my_location_user)
		}

		optionsBtn = mainView.findViewById<ImageView>(R.id.options).apply {
			setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_action_other_menu))
			setOnClickListener { showPopupMenu() }
		}

		mainView.findViewById<ImageView>(R.id.options_icon).apply {
			setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_action_other_menu))
			setOnClickListener { showPopupMenu() }
		}

		textContainer = mainView.findViewById<LinearLayout>(R.id.text_container).apply {
			if (Build.VERSION.SDK_INT >= 16) {
				layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
			}
			AndroidUtils.addStatusBarPadding19v(context!!, this)
			title = findViewById(R.id.title)
			description = findViewById(R.id.description)
		}

		searchBoxBg = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			setColor(ContextCompat.getColor(context!!, R.color.screen_bg_light))
			cornerRadius = (searchBoxHeight / 2).toFloat()
		}

		searchBox = mainView.findViewById<FrameLayout>(R.id.search_box).apply {
			if (Build.VERSION.SDK_INT >= 16) {
				background = searchBoxBg
			} else {
				@Suppress("DEPRECATION")
				setBackgroundDrawable(searchBoxBg)
			}
			findViewById<View>(R.id.search_button).setOnClickListener {
				Toast.makeText(context, "Search", Toast.LENGTH_SHORT).show()
			}
			findViewById<ImageView>(R.id.search_icon)
				.setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_action_search_dark))
		}

		recyclerView = mainView.findViewById<RecyclerView>(R.id.recycler_view).apply {
			layoutManager = LinearLayoutManager(context)
			adapter = this@MyLocationTabFragment.adapter
		}
		switch = mainView.findViewById<Switch>(R.id.stop_all_sharing_switcher).apply {
			if (settings.hasAnyChatToShareLocation()) {
				isChecked = true
			}
			setOnCheckedChangeListener { _, isChecked ->
				run {
					if (!isChecked) {
						app.settings.stopSharingLocationToChats()
						if (!app.settings.hasAnyChatToShareLocation()) {
							app.shareLocationHelper.stopSharingLocation()
						}
						updateList()
						updateSharingMode()
					}
				}
			}
		}

		updateSharingMode()
		
		return mainView
	}

	override fun onResume() {
		super.onResume()
		updateList()
		updateSharingMode()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putLongArray(SELECTED_CHATS_KEY, selectedChats.toLongArray())
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == SetTimeDialogFragment.LOCATION_SHARED_REQUEST_CODE) {
			clearSelection()
			updateList()
			updateSharingMode()
		}
	}

	override fun onTelegramStatusChanged(
		prevTelegramAuthorizationState: TelegramHelper.TelegramAuthorizationState,
		newTelegramAuthorizationState: TelegramHelper.TelegramAuthorizationState
	) {
		when (newTelegramAuthorizationState) {
			TelegramHelper.TelegramAuthorizationState.READY -> {
				updateList()
				updateSharingMode()
			}
			TelegramHelper.TelegramAuthorizationState.CLOSED,
			TelegramHelper.TelegramAuthorizationState.UNKNOWN -> {
				adapter.chats = emptyList()
			}
			else -> Unit
		}
	}

	override fun onTelegramChatsRead() {
		updateList()	
		updateSharingMode()
	}

	override fun onTelegramChatsChanged() {
		updateList()
		updateSharingMode()
	}

	override fun onTelegramChatChanged(chat: TdApi.Chat) {
		updateList()
		updateSharingMode()
	}

	override fun onTelegramUserChanged(user: TdApi.User) {
		updateList()
		updateSharingMode()
	}

	override fun onTelegramError(code: Int, message: String) {
	}

	override fun onSendLiveLocationError(code: Int, message: String) {
	}

	fun onPrimaryBtnClick() {
		val fm = fragmentManager ?: return
		SetTimeDialogFragment.showInstance(fm, selectedChats, this)
	}

	fun onSecondaryBtnClick() {
		clearSelection()
	}

	private fun clearSelection() {
		selectedChats.clear()
		adapter.notifyDataSetChanged()
		actionButtonsListener?.switchButtonsVisibility(false)
	}

	private fun updateSharingMode() {
		hasAnyChatToShareLocation = settings.hasAnyChatToShareLocation()
		if (!hasAnyChatToShareLocation) {
			mainView.findViewById<View>(R.id.tab_title).visibility = View.GONE
			mainView.findViewById<View>(R.id.appbar_divider).visibility = View.GONE
			mainView.findViewById<View>(R.id.stop_all_sharing_row).visibility = View.GONE
			mainView.findViewById<View>(R.id.text_container).visibility = View.VISIBLE
			mainView.findViewById<View>(R.id.background_image).visibility = View.VISIBLE
			val headerParams = mainView.findViewById<View>(R.id.background_image).layoutParams as AppBarLayout.LayoutParams
			headerParams.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
					AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
		} else {
			val headerParams = mainView.findViewById<View>(R.id.background_image).layoutParams as AppBarLayout.LayoutParams
			headerParams.scrollFlags = 0
			mainView.findViewById<View>(R.id.background_image).visibility = View.GONE
			mainView.findViewById<View>(R.id.text_container).visibility = View.GONE
			mainView.findViewById<View>(R.id.tab_title).visibility = View.VISIBLE
			mainView.findViewById<View>(R.id.appbar_divider).visibility = View.VISIBLE
			mainView.findViewById<View>(R.id.stop_all_sharing_row).visibility = View.VISIBLE
			switch.apply {
				if (hasAnyChatToShareLocation) {
					isChecked = true
				}
			}
		}
	}

	private fun showPopupMenu() {
		val ctx = context ?: return

		val menuList = ArrayList<String>()
		val settings = getString(R.string.shared_string_settings)
		val logout = getString(R.string.shared_string_logout)
		val login = getString(R.string.shared_string_login)

		menuList.add(settings)
		@Suppress("NON_EXHAUSTIVE_WHEN")
		when (telegramHelper.getTelegramAuthorizationState()) {
			TelegramHelper.TelegramAuthorizationState.READY -> menuList.add(logout)
			TelegramHelper.TelegramAuthorizationState.CLOSED -> menuList.add(login)
		}

		ListPopupWindow(ctx).apply {
			isModal = true
			anchorView = optionsBtn
			setContentWidth(AndroidUtils.getPopupMenuWidth(ctx, menuList))
			setDropDownGravity(Gravity.END or Gravity.TOP)
			setAdapter(ArrayAdapter(ctx, R.layout.popup_list_text_item, menuList))
			setOnItemClickListener { _, _, position, _ ->
				when (position) {
					menuList.indexOf(settings) -> {
						fragmentManager?.also { SettingsDialogFragment.showInstance(it) }
					}
					menuList.indexOf(logout) -> logoutTelegram()
					menuList.indexOf(login) -> loginTelegram()
				}
				dismiss()
			}
			show()
		}
	}

	private fun logoutTelegram(silent: Boolean = false) {
		if (telegramHelper.getTelegramAuthorizationState() == TelegramHelper.TelegramAuthorizationState.READY) {
			telegramHelper.logout()
		} else if (!silent) {
			Toast.makeText(context, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
		}
	}

	private fun loginTelegram() {
		if (telegramHelper.getTelegramAuthorizationState() != TelegramHelper.TelegramAuthorizationState.CLOSED) {
			telegramHelper.logout()
		}
		telegramHelper.init()
	}

	private fun adjustText() {
		val gravity = if (appBarCollapsed) Gravity.START else Gravity.CENTER
		val padding = if (appBarCollapsed) textMarginSmall else textMarginBig
		textContainer.apply {
			setPadding(padding, paddingTop, padding, paddingBottom)
		}
		title.gravity = gravity
		description.gravity = gravity
	}

	private fun adjustAppbar() {
		updateTitleTextColor()
		if (Build.VERSION.SDK_INT >= 21) {
			if (appBarCollapsed) {
				appBarLayout.outlineProvider = appBarOutlineProvider
			} else {
				appBarLayout.outlineProvider = null
			}
		}
	}

	private fun adjustSearchBox() {
		val cornerRadiusFrom = if (appBarCollapsed) searchBoxHeight / 2 else 0
		val cornerRadiusTo = if (appBarCollapsed) 0 else searchBoxHeight / 2
		val marginFrom = if (appBarCollapsed) searchBoxSidesMargin else 0
		val marginTo = if (appBarCollapsed) 0 else searchBoxSidesMargin

		val cornerAnimator = ObjectAnimator.ofFloat(
			searchBoxBg,
			"cornerRadius",
			cornerRadiusFrom.toFloat(),
			cornerRadiusTo.toFloat()
		)

		val marginAnimator = ValueAnimator.ofInt(marginFrom, marginTo)
		marginAnimator.addUpdateListener {
			val value = it.animatedValue as Int
			val params = searchBox.layoutParams as LinearLayout.LayoutParams
			params.setMargins(value, params.topMargin, value, params.bottomMargin)
			searchBox.layoutParams = params
		}

		AnimatorSet().apply {
			duration = 200
			playTogether(cornerAnimator, marginAnimator)
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator?) {
					updateTitleTextColor()
					if (appBarCollapsed && Build.VERSION.SDK_INT >= 21) {
						appBarLayout.outlineProvider = appBarOutlineProvider
					}
				}
			})
			start()
		}

		if (!appBarCollapsed && Build.VERSION.SDK_INT >= 21) {
			appBarLayout.outlineProvider = null
		}
	}

	private fun updateTitleTextColor() {
		val color = if (appBarCollapsed) R.color.app_bar_title_light else R.color.ctrl_active_light
		context?.also {
			title.setTextColor(ContextCompat.getColor(it, color))
		}
	}

	private fun updateList() {
		hasAnyChatToShareLocation = settings.hasAnyChatToShareLocation()
		if (hasAnyChatToShareLocation) {
			val chatList = settings.getShareLocationChats()
			val chats: MutableList<TdApi.Chat> = mutableListOf()
			val currentUser = telegramHelper.getCurrentUser()
			for (orderedChat in chatList) {
				val chat = telegramHelper.getChat(orderedChat)
				if (chat != null) {
					if (telegramHelper.isPrivateChat(chat)) {
						if ((chat.type as TdApi.ChatTypePrivate).userId == currentUser?.id) {
							continue
						}
					}
					chats.add(chat)
				}
			}
			adapter.chats = chats
		} else {
			val chatList = telegramHelper.getChatList()
			val chats: MutableList<TdApi.Chat> = mutableListOf()
			val currentUser = telegramHelper.getCurrentUser()
			for (orderedChat in chatList) {
				val chat = telegramHelper.getChat(orderedChat.chatId)
				if (chat != null) {
					if (telegramHelper.isPrivateChat(chat)) {
						if ((chat.type as TdApi.ChatTypePrivate).userId == currentUser?.id) {
							continue
						}
					}
					chats.add(chat)
				}
			}
			adapter.chats = chats
		}
	}

	inner class MyLocationListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		var chats: List<TdApi.Chat> = emptyList()
			set(value) {
				field = value
				notifyDataSetChanged()
			}

		override fun getItemViewType(position: Int): Int {
			return if (settings.isSharingLocationToChat(chats[position].id)) {
				1
			} else {
				0
			}
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			return if (viewType == 1) {
				val view = LayoutInflater.from(parent.context)
					.inflate(R.layout.live_now_chat_my_location, parent, false)
				ChatViewHolder2(view)
			} else {
				val view = LayoutInflater.from(parent.context)
					.inflate(R.layout.user_list_item, parent, false)
				ChatViewHolder(view)
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val chat = chats[position]
			val lastItem = position == itemCount - 1
			val placeholderId =
				if (telegramHelper.isGroup(chat)) R.drawable.img_group_picture else R.drawable.img_user_picture
			val live = app.settings.isSharingLocationToChat(chat.id)

			if (holder is ChatViewHolder) {
				TelegramUiHelper.setupPhoto(
					app,
					holder.icon,
					chat.photo?.small?.local?.path,
					placeholderId,
					false
				)
				holder.title?.text = chat.title
				holder.description?.visibility = View.GONE
				if (live) {
					holder.checkBox?.visibility = View.GONE
				} else {
					holder.checkBox?.apply {
						visibility = View.VISIBLE
						setOnCheckedChangeListener(null)
						isChecked = selectedChats.contains(chat.id)
						setOnCheckedChangeListener { _, isChecked ->
							if (isChecked) {
								selectedChats.add(chat.id)
							} else {
								selectedChats.remove(chat.id)
							}
							actionButtonsListener?.switchButtonsVisibility(selectedChats.isNotEmpty())
						}
					}
				}
				holder.bottomShadow?.visibility = if (lastItem) View.VISIBLE else View.GONE
				holder.itemView.setOnClickListener {
					if (live) {
						app.settings.shareLocationToChat(chat.id, false)
						if (!app.settings.hasAnyChatToShareLocation()) {
							app.shareLocationHelper.stopSharingLocation()
						}
						notifyItemChanged(position)
					} else {
						holder.checkBox?.apply {
							isChecked = !isChecked
						}
					}
				}
			} else if (holder is ChatViewHolder2) {
				TelegramUiHelper.setupPhoto(
					app,
					holder.icon,
					chat.photo?.small?.local?.path,
					placeholderId,
					false
				)
				holder.title?.text = chat.title
				holder.switcher?.apply {
					if (live) {
						isChecked = true
					}
					setOnCheckedChangeListener { _, isChecked ->
						run {
							if (!isChecked) {
								app.settings.shareLocationToChat(chat.id, false)
								if (!app.settings.hasAnyChatToShareLocation()) {
									app.shareLocationHelper.stopSharingLocation()
								}
								updateList()
								updateSharingMode()
							}
						}
					}
				}
				val duration = settings.getChatLivePeriod(chat.id)
				if (duration != null && duration > 0) {
					holder.descriptionDuration?.text = formatTime(duration)
				}
			}
		}

		override fun getItemCount() = chats.size

		private fun formatTime(timeSec: Long): String {
			val hours = TimeUnit.SECONDS.toHours(timeSec)
			val minutes = TimeUnit.SECONDS.toMinutes(timeSec) % 60
			var hoursS = ""
			var minutesS = ""
			if (hours > 0) {
				hoursS = String.format("%2d h ", hours)
			}
			if (minutes > 0) {
				minutesS = String.format("%2d min", minutes)
			}
			return hoursS + minutesS
		}

		private fun formatTimeAt(timeSec: Long): String {
			val hours = TimeUnit.SECONDS.toHours(timeSec)
			val minutes = TimeUnit.SECONDS.toMinutes(timeSec) % 60
			return String.format("%2d:%2d", hours, minutes)
		}

		inner class ChatViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
			val icon: ImageView? = view.findViewById(R.id.icon)
			val title: TextView? = view.findViewById(R.id.title)
			val description: TextView? = view.findViewById(R.id.description)
			val checkBox: CheckBox? = view.findViewById(R.id.check_box)
			val bottomShadow: View? = view.findViewById(R.id.bottom_shadow)
		}

		inner class ChatViewHolder2(val view: View) : RecyclerView.ViewHolder(view) {
			val icon: ImageView? = view.findViewById(R.id.icon)
			val title: TextView? = view.findViewById(R.id.title)
			val description: TextView? = view.findViewById(R.id.description)
			val descriptionDuration: TextView? = view.findViewById(R.id.duration)
			val textInArea: TextView? = view.findViewById(R.id.text_in_area)
			val switcher: Switch? = view.findViewById(R.id.switcher)
			val stopTranslation: TextView? = view.findViewById(R.id.stop_translation_in_tv_1)
			val stopIn: TextView? = view.findViewById(R.id.stop_translation_in_tv_2)
		}
	}

	interface ActionButtonsListener {
		fun switchButtonsVisibility(visible: Boolean)
	}
}
