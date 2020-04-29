package com.gzozulin.everyday

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import butterknife.BindView
import butterknife.ButterKnife
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.jakewharton.threetenabp.AndroidThreeTen
import com.kizitonwose.calendarview.CalendarView
import com.kizitonwose.calendarview.model.CalendarDay
import com.kizitonwose.calendarview.model.CalendarMonth
import com.kizitonwose.calendarview.ui.DayBinder
import com.kizitonwose.calendarview.ui.MonthHeaderFooterBinder
import com.kizitonwose.calendarview.ui.ViewContainer
import com.kizitonwose.calendarview.utils.next
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import kotlinx.coroutines.*
import nl.dionsegijn.konfetti.KonfettiView
import nl.dionsegijn.konfetti.models.Shape
import nl.dionsegijn.konfetti.models.Size
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.provider
import org.kodein.di.generic.singleton
import org.threeten.bp.*
import org.threeten.bp.format.TextStyle
import org.threeten.bp.temporal.WeekFields
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

// region --------------------- ToDo --------------------------------

// todo: launcher icon
// todo: increment score anim
// todo: rearrange the backlog?
// todo: set reminder when device boots
// todo: export score as well
// todo: self-set prizes
// todo: crash reporting

// endregion --------------------- ToDo --------------------------------

// region --------------------- Constants --------------------------------

private const val IS_DEBUG = false
private const val MIN_CURRENT = 3
private const val CURRENT_PART = 0.4f
private const val PROGRESS_FULL = 10
private const val SCORE_MAX = 10f
private const val CHANCE_TO_DOWNPLAY = 0.2
private const val FLOAT_FORMAT = "%.1f"
private const val NORMAL_SCORE = 3.9
private const val GREAT_SCORE = 7.9
private const val KEY_ADVANCED = "com.gzozulin.advanced"
private const val NOTIFICATION_CHANNEL_ID = "com.gzozulin.remainder"
private const val NOTIFICATION_ID = 123
private const val HOUR_TO_REMIND = 21
private const val KEY_LAST_REMIND = "com.gzozulin.last_remind"

private val weekends = listOf(Calendar.SATURDAY, Calendar.SUNDAY)

private val patternNewLine = "\n".toPattern()
private val patternCsv = ";".toPattern()

private val random = Random()

// endregion --------------------- Constants --------------------------------

// region --------------------- Application --------------------------------

lateinit var appContext: Context

val kodein: Kodein = Kodein {
    bind<Context>()             with provider { appContext }
    bind<EverydayDb>()          with singleton { Room
        .databaseBuilder(instance(), EverydayDb::class.java, "everyday-db")
        .fallbackToDestructiveMigration().build() }
    bind<RoutineDao>()          with provider { instance<EverydayDb>().routineDao() }
    bind<ScoreDao>()            with provider { instance<EverydayDb>().scoreDao() }
    bind<EverydayKeyValue>()    with singleton { EverydayKeyValue(instance()) }
    bind<EverydayViewModel>()   with provider { viewModel }
}

class EverydayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this)
        appContext = applicationContext
        updateReminderAlarm(this)
    }
}

// endregion --------------------- Application --------------------------------

// region --------------------- Storage --------------------------------

enum class RoutineState {
    BACKLOG, CURRENT, LEARNED, PAUSED
}

@Entity
data class Routine(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    @ColumnInfo var label: String,
    @ColumnInfo var progress: Int = 0,
    @ColumnInfo var state: RoutineState = RoutineState.BACKLOG,
    @ColumnInfo var finishedToday: Boolean = false,

    var currentScore: Float = 0f) {

    val isLearned: Boolean
        get() = progress == PROGRESS_FULL

    val lackingProgress: Int
        get() = PROGRESS_FULL - progress

    val fullProgress: Int
        get() = progress + if (finishedToday) 1 else 0

    val isCurrent: Boolean
        get() = state == RoutineState.CURRENT
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routine")
    fun subscribeAll(): Observable<List<Routine>>

    @Query("SELECT * FROM routine")
    suspend fun getAll(): List<Routine>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routines: List<Routine>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: Routine)

    @Delete
    suspend fun delete(routine: Routine)

    @Query("DELETE FROM routine")
    fun deleteAll()
}

@Entity
data class Score (
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    @ColumnInfo val timestamp: Long,
    @ColumnInfo val score: Float
)

@Dao
interface ScoreDao {
    @Query("SELECT * FROM score")
    fun subscribeAll(): Observable<List<Score>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(score: Score)
}

class EverydayConverters {
    @TypeConverter
    fun fromRoutineState(state: RoutineState) = state.ordinal

    @TypeConverter
    fun toRoutineState(state: Int) = RoutineState.values()[state]
}

@Database(entities = [Routine::class, Score::class], version = 7)
@TypeConverters(EverydayConverters::class)
abstract class EverydayDb : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun scoreDao(): ScoreDao
}

private class EverydayKeyValue(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("com.gzozulin.preferences", Context.MODE_PRIVATE)

    suspend fun shouldRemindToday() = withContext(Dispatchers.IO) {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastRemind = preferences.getInt(KEY_LAST_REMIND, -1)
        if (today != lastRemind) {
            preferences.edit().putInt(KEY_LAST_REMIND, today).apply()
            return@withContext true
        }
        return@withContext false
    }
}

// endregion --------------------- Storage --------------------------------

// region --------------------- ViewModel --------------------------------

data class SortedRoutines(
    val backlog: MutableList<Routine>,
    val current: MutableList<Routine>,
    val learned: MutableList<Routine>,
    val paused: MutableList<Routine>
)

private fun sortRoutinesByState(routines: List<Routine>): SortedRoutines {
    val backlog = mutableListOf<Routine>()
    val current = mutableListOf<Routine>()
    val learned = mutableListOf<Routine>()
    val paused = mutableListOf<Routine>()
    for (routine in routines) {
        when (routine.state) {
            RoutineState.BACKLOG -> backlog.add(routine)
            RoutineState.CURRENT -> current.add(routine)
            RoutineState.LEARNED -> learned.add(routine)
            RoutineState.PAUSED -> paused.add(routine)
        }
    }
    return SortedRoutines(backlog, current, learned, paused)
}

class EverydayViewModel : ViewModel() {
    private val routinesDao by kodein.instance<RoutineDao>()
    private val scoreDao by kodein.instance<ScoreDao>()
    private val appContext by kodein.instance<Context>()

    private val exported = File(appContext.getExternalFilesDir(null), "everyday_exported")

    private val disposable = CompositeDisposable()

    val learnedRoutines = BehaviorSubject.create<List<Routine>>()
    val currentRoutines = BehaviorSubject.create<List<Routine>>()
    val backlogRoutines = BehaviorSubject.create<List<Routine>>()
    val pausedRoutines = BehaviorSubject.create<List<Routine>>()

    val dailyScores = BehaviorSubject.create<Map<LocalDate, Float>>()

    val score: Observable<Float> = currentRoutines.map { calculateCurrentScore(it) }
    val konfetti: Observable<Float> = score.filter { it > GREAT_SCORE }

    init {
        disposable.add(routinesDao.subscribeAll()
            .subscribeOn(Schedulers.io())
            .subscribe { routines ->
                val (backlog, current, learned, paused) = sortRoutinesByState(routines)
                current.sortBy { it.progress }
                backlog.sortBy { it.progress }
                updateScores(current)
                backlogRoutines.onNext(backlog)
                currentRoutines.onNext(current)
                learnedRoutines.onNext(learned)
                pausedRoutines.onNext(paused)
            })
        disposable.add(scoreDao.subscribeAll()
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .map { scores ->
                val result = mutableMapOf<LocalDate, Float>()
                for (score in scores) {
                    val localDateTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(score.timestamp), ZoneId.systemDefault())
                    result[localDateTime.toLocalDate()] = score.score
                }
                return@map result
            }
            .subscribe { daily -> dailyScores.onNext(daily) })
    }

    override fun onCleared() {
        disposable.dispose()
        super.onCleared()
    }

    private fun updateScores(current: List<Routine>) {
        var allProgress = 0
        for (routine in current) {
            allProgress += routine.lackingProgress
        }
        val scoreForPoint = SCORE_MAX / allProgress
        for (routine in current) {
            routine.currentScore = routine.lackingProgress * scoreForPoint
        }
    }

    private fun calculateCurrentScore(current: List<Routine>): Float {
        var score = 0f
        for (routine in current) {
            if (routine.finishedToday) {
                score += routine.currentScore
            }
        }
        return score
    }

    private fun todayIsWeekend(): Boolean {
        return weekends.contains(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
    }

    fun advance(timestamp: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val routines = routinesDao.getAll()
                val (backlog, current, learned, _) = sortRoutinesByState(routines)
                scoreDao.insert(Score(0L, timestamp = timestamp, score = calculateCurrentScore(current)))
                val currIter = current.iterator()
                while (currIter.hasNext()) {
                    val routine = currIter.next()
                    if (routine.finishedToday) {
                        routine.finishedToday = false
                        routine.progress += 1
                        if (routine.isLearned) {
                            routine.state = RoutineState.LEARNED
                            currIter.remove()
                            learned.add(routine)
                        }
                    } else if (routine.progress > 0 && !todayIsWeekend()) {
                        routine.progress -= 1
                    }
                }
                val learIter = learned.iterator()
                while (learIter.hasNext()) {
                    val routine = learIter.next()
                    if (random.nextFloat() < CHANCE_TO_DOWNPLAY) {
                        routine.progress -= 1
                        routine.state = RoutineState.BACKLOG
                        learIter.remove()
                        backlog.add(routine)
                    }
                }
                routinesDao.insertAll(backlog)
                routinesDao.insertAll(current)
                routinesDao.insertAll(learned)
            }
        }
        refillCurrentFromBacklog()
    }

    private fun refillCurrentFromBacklog() {
        viewModelScope.launch {
            val routines = routinesDao.getAll()
            val (backlog, current, learned, _) = sortRoutinesByState(routines)
            val activeCnt = backlog.size + current.size + learned.size
            val expectedCurrentCnt = maxOf(MIN_CURRENT, (activeCnt * CURRENT_PART).toInt())
            if (current.size >= expectedCurrentCnt) {
                return@launch
            }
            backlog.sortBy { it.progress }
            while (backlog.isNotEmpty() && current.size < expectedCurrentCnt) {
                val routine = backlog.removeAt(0)
                routine.state = RoutineState.CURRENT
                current.add(routine)
            }
            routinesDao.insertAll(backlog)
            routinesDao.insertAll(current)
        }
    }

    fun addRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routinesDao.insert(routine)
            }
        }
        refillCurrentFromBacklog()
    }

    // todo: make specific instances of the call (rename, finish, etc)
    fun updateRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routinesDao.insert(routine)
            }
        }
    }

    fun deleteRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routinesDao.delete(routine)
            }
        }
        refillCurrentFromBacklog()
    }

    fun pauseRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routine.state = RoutineState.PAUSED
                routinesDao.insert(routine)
                refillCurrentFromBacklog()
            }
        }
    }

    fun continueRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routine.state = if (routine.isLearned) RoutineState.LEARNED else RoutineState.BACKLOG
                routine.finishedToday = false
                routinesDao.insert(routine)
            }
        }
        refillCurrentFromBacklog()
    }

    fun exportRoutines() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = routinesDao.getAll()
                var result = ""
                for (routine in all) {
                    result += "${routine.label};" +
                            "${routine.progress};" +
                            "${routine.state};" +
                            "${routine.finishedToday}\n"
                }
                exported.writeText(result)
            }
        }
    }

    fun importRoutines() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val result = mutableListOf<Routine>()
                val text = exported.readText()
                val lines = text.split(patternNewLine)
                for (line in lines) {
                    if (line.isBlank()) {
                        continue
                    }
                    val split = line.split(patternCsv)
                    check(split.size == 4) { "Wtf? $line" }
                    result.add(Routine(
                        label = split[0],
                        progress = split[1].toInt(),
                        state = RoutineState.valueOf(split[2]),
                        finishedToday = split[3].toBoolean()))
                }
                routinesDao.deleteAll()
                routinesDao.insertAll(result)
            }
        }
    }
}

// endregion --------------------- ViewModel --------------------------------

// region --------------------- Activity --------------------------------

private lateinit var viewModel: EverydayViewModel

class MainActivity : AppCompatActivity() {
    private val disposable = CompositeDisposable()

    @BindView(R.id.pager)
    lateinit var viewPager: ViewPager2

    @BindView(R.id.tab_layout)
    lateinit var tabLayout: TabLayout

    @BindView(R.id.konfetti)
    lateinit var viewKonfetti: KonfettiView

    @BindView(R.id.add)
    lateinit var addButton: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(EverydayViewModel::class.java)
        setContentView(R.layout.activity_main)
        ButterKnife.bind(this)
        createTabs()
        subscribeKonfetti()
        bootstrapAdd()
        createNotificationChannel(this)
    }

    override fun onResume() {
        super.onResume()
        checkIfAdvance()
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
    }

    private fun createTabs() {
        viewPager.adapter = EverydayTabsAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when(position) {
                0 -> "ROUTINES"
                1 -> "CALENDAR"
                else -> TODO()
            }
        }.attach()
    }

    private fun bootstrapAdd() {
        addButton.setOnClickListener {
            showAddDialog(this)
        }
    }

    private fun subscribeKonfetti() {
        disposable.add(viewModel.konfetti
            .debounce(1, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(500)
                }
                Handler(Looper.getMainLooper()).post {
                    viewKonfetti.build()
                        .addColors(Color.YELLOW, Color.GREEN, Color.MAGENTA, Color.BLUE, Color.RED)
                        .setDirection(0.0, 359.0)
                        .setSpeed(1f, 5f)
                        .setFadeOutEnabled(true)
                        .setTimeToLive(2000L)
                        .addShapes(Shape.Square, Shape.Circle)
                        .addSizes(Size(12))
                        .setPosition(-50f, viewKonfetti.width + 50f, -50f, -50f)
                        .streamFor(100, 1500L)
                }
        })
    }

    private fun checkIfAdvance() {
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                val preferences = getPreferences(Context.MODE_PRIVATE)
                val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                val advanced = preferences.getInt(KEY_ADVANCED, today)
                var times = today - advanced
                var timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(times.toLong())
                while (times > 0) {
                    viewModel.advance(timestamp)
                    times--
                    timestamp += TimeUnit.DAYS.toMillis(1)
                }
                preferences.edit().putInt(KEY_ADVANCED, today).apply()
            }
        }
    }
}

class EverydayTabsAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> RoutinesFragment()
            1 -> CalendarFragment()
            else -> TODO()
        }
    }
}

fun View.animateVisible() {
    if (visibility != View.VISIBLE) {
        alpha = 0f
        animate().alpha(1f).duration = 500
        visibility = View.VISIBLE
    }
}

fun scoreToColor(score: Float) = when {
    score > GREAT_SCORE -> Color.parseColor("#0DE320")
    score > NORMAL_SCORE -> Color.parseColor("#EAC108")
    else -> Color.parseColor("#000000")
}

// endregion --------------------- Activity --------------------------------

// region --------------------- RoutinesFragment --------------------------------

class RoutinesFragment : Fragment() {
    private val disposable = CompositeDisposable()

    @BindView(R.id.score)
    lateinit var scoreTextView: TextView

    @BindView(R.id.header_current)
    lateinit var headerCurrentView: View

    @BindView(R.id.current)
    lateinit var currentList: RecyclerView

    @BindView(R.id.header_backlog)
    lateinit var headerBacklogView: View

    @BindView(R.id.backlog)
    lateinit var backlogList: RecyclerView

    @BindView(R.id.header_learned)
    lateinit var headerLearnedView: View

    @BindView(R.id.learned)
    lateinit var learnedList: RecyclerView

    @BindView(R.id.header_paused)
    lateinit var headerPausedView: View

    @BindView(R.id.paused)
    lateinit var pausedList: RecyclerView

    @BindView(R.id.advance)
    lateinit var advanceButton: Button

    @BindView(R.id.exportBtn)
    lateinit var exportButton: Button

    @BindView(R.id.importBtn)
    lateinit var importButton: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = LayoutInflater.from(context).inflate(R.layout.fragment_routines, container)
        ButterKnife.bind(this, view)
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        subscribeCurrentScore()
        subscribeRoutines()
        bootstrapAdvance()
        bootstrapExportImport()
    }

    override fun onDestroyView() {
        disposable.clear()
        super.onDestroyView()
    }

    private fun subscribeCurrentScore() {
        disposable.add(viewModel.score.observeOn(AndroidSchedulers.mainThread())
            .subscribe { score ->
                scoreTextView.animateVisible()
                scoreTextView.text = FLOAT_FORMAT.format(score)
                scoreTextView.setTextColor(scoreToColor(score))
            })
    }

    private fun subscribeRoutines() {
        backlogList.layoutManager = LinearLayoutManager(context)
        disposable.add(viewModel.backlogRoutines.observeOn(AndroidSchedulers.mainThread()).subscribe {
            if (it.isNotEmpty()) {
                headerBacklogView.animateVisible()
                backlogList.animateVisible()
                backlogList.adapter = RoutinesAdapter(it)
            } else {
                headerBacklogView.visibility = View.GONE
                backlogList.visibility = View.GONE
            }
        })
        currentList.layoutManager = LinearLayoutManager(context)
        disposable.add(viewModel.currentRoutines.observeOn(AndroidSchedulers.mainThread()).subscribe {
            if (it.isNotEmpty()) {
                headerCurrentView.animateVisible()
                currentList.animateVisible()
                currentList.adapter = RoutinesAdapter(it)
            } else {
                headerCurrentView.visibility = View.GONE
                currentList.visibility = View.GONE
            }
        })
        learnedList.layoutManager = LinearLayoutManager(context)
        disposable.add(viewModel.learnedRoutines.observeOn(AndroidSchedulers.mainThread()).subscribe {
            if (it.isNotEmpty()) {
                headerLearnedView.animateVisible()
                learnedList.animateVisible()
                learnedList.adapter = RoutinesAdapter(it)
            } else {
                headerLearnedView.visibility = View.GONE
                learnedList.visibility = View.GONE
            }
        })
        pausedList.layoutManager = LinearLayoutManager(context)
        disposable.add(viewModel.pausedRoutines.observeOn(AndroidSchedulers.mainThread()).subscribe {
            if (it.isNotEmpty()) {
                headerPausedView.animateVisible()
                pausedList.animateVisible()
                pausedList.adapter = RoutinesAdapter(it)
            } else {
                headerPausedView.visibility = View.GONE
                pausedList.visibility = View.GONE
            }
        })
    }

    private fun bootstrapExportImport() {
        exportButton.setOnClickListener {
            viewModel.exportRoutines()
        }
        exportButton.visibility = if (IS_DEBUG) View.VISIBLE else View.GONE
        importButton.setOnClickListener {
            viewModel.importRoutines()
        }
        importButton.visibility = if (IS_DEBUG) View.VISIBLE else View.GONE
    }

    private fun bootstrapAdvance() {
        advanceButton.setOnClickListener {
            viewModel.advance(System.currentTimeMillis())
        }
        advanceButton.visibility = if (IS_DEBUG) View.VISIBLE else View.GONE
    }
}

// endregion --------------------- RoutinesFragment --------------------------------

// region --------------------- CalendarFragment --------------------------------

class CalendarFragment : Fragment() {
    private val disposable = CompositeDisposable()

    @BindView(R.id.calendar)
    lateinit var calendarView: CalendarView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = LayoutInflater.from(context).inflate(R.layout.fragment_calendar, container)
        ButterKnife.bind(this, view)
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        subscribeCalendar()
    }

    override fun onDestroyView() {
        disposable.clear()
        super.onDestroyView()
    }

    private fun subscribeCalendar() {
        disposable.add(viewModel.dailyScores
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { dailyScores ->
                    GlobalScope.launch {
                        withContext(Dispatchers.Default) {
                            calendarView.dayBinder = EverydayDayBinder(dailyScores)
                            calendarView.monthHeaderBinder = EverydayMonthHeaderBinder()
                        }
                        withContext(Dispatchers.Main) {
                            val currentMonth = YearMonth.now()
                            val firstMonth = currentMonth.minusMonths(11)
                            val lastMonth = currentMonth.next
                            val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
                            calendarView.setup(firstMonth, lastMonth, firstDayOfWeek)
                            calendarView.animateVisible()
                            calendarView.scrollToMonth(currentMonth)
                        }
                    }
                })
    }
}

private class EverydayDayBinder(val dailyScores: Map<LocalDate, Float>) : DayBinder<DayViewContainer> {
    override fun create(view: View) = DayViewContainer(view)

    override fun bind(container: DayViewContainer, day: CalendarDay) {
        val score: Float? = dailyScores[day.date]
        container.bind(day, score)
    }
}

private class DayViewContainer(view: View) : ViewContainer(view) {
    @BindView(R.id.dayText)
    lateinit var textView: TextView

    init {
        ButterKnife.bind(this, view)
    }

    fun bind(day: CalendarDay, score: Float?) {
        textView.text = day.date.dayOfMonth.toString()
        if (score != null && score > NORMAL_SCORE) {
            textView.setTextColor(scoreToColor(score))
        }
    }
}

private class EverydayMonthHeaderBinder : MonthHeaderFooterBinder<MonthHeaderContainer> {
    override fun create(view: View) = MonthHeaderContainer(view)

    override fun bind(container: MonthHeaderContainer, month: CalendarMonth) {
        container.bind(month)
    }
}

private class MonthHeaderContainer(view: View) : ViewContainer(view) {
    @BindView(R.id.monthText)
    lateinit var monthText: TextView

    init {
        ButterKnife.bind(this, view)
    }

    fun bind(month: CalendarMonth) {
        monthText.text = Month.of(month.month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.CANADA)
    }
}

// endregion --------------------- CalendarFragment --------------------------------

// region --------------------- Dialogs --------------------------------

private fun showAddDialog(context: Context) {
    val builder = AlertDialog.Builder(context)
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_add, null)
    builder.setView(view)
    val dialog = builder.create()
    val addButton: Button = view.findViewById(R.id.add)!!
    val labelEditText: EditText = view.findViewById(R.id.label)!!
    labelEditText.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(editable: Editable?) {
            addButton.visibility = if (editable!!.isNotEmpty()) View.VISIBLE else View.GONE
        }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
        }
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
        }
    })
    addButton.setOnClickListener {
        dialog.dismiss()
        viewModel.addRoutine(Routine(label = labelEditText.text.toString()))
    }
    dialog.show()
}

private fun showUpdateDialog(context: Context, routine: Routine) {
    val builder = AlertDialog.Builder(context)
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
    builder.setView(view)
    val dialog = builder.create()
    val deleteButton: Button = view.findViewById(R.id.delete)!!
    val pauseButton: Button = view.findViewById(R.id.pause)!!
    val continueButton: Button = view.findViewById(R.id.continueBtn)!!
    val updateButton: Button = view.findViewById(R.id.update)!!
    val labelEditText: EditText = view.findViewById(R.id.label)!!
    labelEditText.setText(routine.label)
    labelEditText.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(editable: Editable?) {
            updateButton.visibility = if (editable!!.isNotEmpty()) View.VISIBLE else View.GONE
        }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
        }
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
        }
    })
    deleteButton.setOnClickListener {
        dialog.dismiss()
        viewModel.deleteRoutine(routine)
    }
    pauseButton.setOnClickListener {
        dialog.dismiss()
        viewModel.pauseRoutine(routine)
    }
    pauseButton.visibility = if (routine.state != RoutineState.PAUSED) View.VISIBLE else View.GONE
    continueButton.setOnClickListener {
        dialog.dismiss()
        viewModel.continueRoutine(routine)
    }
    continueButton.visibility = if (routine.state == RoutineState.PAUSED) View.VISIBLE else View.GONE
    updateButton.setOnClickListener {
        dialog.dismiss()
        routine.label = labelEditText.text.toString()
        viewModel.updateRoutine(routine)
    }
    dialog.show()
}

// endregion --------------------- Dialogs --------------------------------

// region --------------------- Adapter --------------------------------

private class RoutineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val viewModel by kodein.instance<EverydayViewModel>()

    @BindView(R.id.progress)
    lateinit var progressProgressBar: ProgressBar

    @BindView(R.id.label)
    lateinit var labelTextView: TextView

    @BindView(R.id.done)
    lateinit var doneButton: Button

    @BindView(R.id.undo)
    lateinit var undoButton: Button

    init {
        ButterKnife.bind(this, itemView)
    }

    fun bind(routine: Routine) {
        labelTextView.text = routine.label
        labelTextView.isEnabled = routine.state != RoutineState.PAUSED
        if (routine.progress != 0) {
            progressProgressBar.visibility = View.VISIBLE
            progressProgressBar.progress = routine.fullProgress
            progressProgressBar.max = PROGRESS_FULL
        } else {
            progressProgressBar.visibility = View.GONE
        }
        doneButton.text = FLOAT_FORMAT.format(routine.currentScore)
        doneButton.visibility = if (routine.isCurrent && !routine.finishedToday) View.VISIBLE else View.GONE
        doneButton.setOnClickListener {
            routine.finishedToday = true
            viewModel.updateRoutine(routine)
        }
        undoButton.visibility = if (routine.isCurrent && routine.finishedToday) View.VISIBLE else View.GONE
        undoButton.setOnClickListener {
            routine.finishedToday = false
            viewModel.updateRoutine(routine)
        }
        itemView.setOnLongClickListener {
            showUpdateDialog(itemView.context, routine)
            return@setOnLongClickListener true
        }
    }
}

private class RoutinesAdapter(val items: List<Routine>)
    : RecyclerView.Adapter<RoutineViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoutineViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(R.layout.item_routine, parent, false)
        return RoutineViewHolder(root)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RoutineViewHolder, position: Int) {
        holder.bind(items[position])
    }
}

// endregion --------------------- Adapter --------------------------------

// region --------------------- Reminder --------------------------------

private fun updateReminderAlarm(context: Context) {
    val notificationIntent = Intent(context, ReminderPublisher::class.java)
    val pendingIntent = PendingIntent.getBroadcast(context, 0, notificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val calendar: Calendar = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, HOUR_TO_REMIND)
    }
    alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        AlarmManager.INTERVAL_DAY, pendingIntent)
}

class ReminderPublisher : BroadcastReceiver() {
    private val routineDao by kodein.instance<RoutineDao>()
    private val preferences by kodein.instance<EverydayKeyValue>()

    override fun onReceive(context: Context, intent: Intent) {
        runBlocking(Dispatchers.IO) {
            if (preferences.shouldRemindToday()) {
                val all = routineDao.getAll()
                val (_, current) = sortRoutinesByState(all)
                current.forEach {
                    if (!it.finishedToday) {
                        showReminderNotification(context)
                        return@runBlocking
                    }
                }
            }
        }
    }
}

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Everyday reminders", importance)
        channel.description = "Missed routines for today"
        val notificationManager: NotificationManager =
            getSystemService(context, NotificationManager::class.java) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

private suspend fun showReminderNotification(context: Context) = withContext(Dispatchers.Main) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
    val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID) else
        NotificationCompat.Builder(context))
    builder
        .setSmallIcon(R.drawable.remainder)
        .setContentTitle("Everyday reminder")
        .setContentText("Some of your routines aren't marked as being finished")
        .setStyle(NotificationCompat.BigTextStyle()
            .bigText("Some of your routines aren't marked as being finished"))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .priority = NotificationCompat.PRIORITY_DEFAULT
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
}

// endregion --------------------- Reminder --------------------------------
