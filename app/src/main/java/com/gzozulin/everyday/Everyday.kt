package com.gzozulin.everyday

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
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
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.jakewharton.threetenabp.AndroidThreeTen
import com.kizitonwose.calendarview.CalendarView
import com.kizitonwose.calendarview.model.CalendarDay
import com.kizitonwose.calendarview.model.CalendarMonth
import com.kizitonwose.calendarview.model.DayOwner
import com.kizitonwose.calendarview.ui.DayBinder
import com.kizitonwose.calendarview.ui.MonthHeaderFooterBinder
import com.kizitonwose.calendarview.ui.ViewContainer
import com.kizitonwose.calendarview.utils.next
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import kotlinx.android.synthetic.main.calendar_day_layout.view.*
import kotlinx.android.synthetic.main.calendar_month_header.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

// region --------------------- ToDo --------------------------------

// todo: launcher icon
// todo: increment score anim
// todo: debounce for konffetti
// todo: 10 points - show a small badge
// todo: show median score for the month
// todo: self-set prizes

// todo: fix reminder
// todo: set reminder when device boots

// todo: export to the place available for the release

// todo: crash reporting
// todo: example routines on the first start

// todo: maybe weight for items to prioritise points?

// todo: add feedback option

// todo: bug with layout after adding routine

// todo: some items are available only during time frame (breakfast before 12)

// todo: bug: advancement happens on the next day, but weekend counts today? (check for prev day)

// todo: everytime: other cyclic rates (once in a month, once in a week, once in a year)

// endregion --------------------- ToDo --------------------------------

// region --------------------- Constants --------------------------------

private const val IS_DEBUG = false

private const val PROGRESS_FULL = 10
private const val SCORE_MAX = 10f
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
        //updateReminderAlarm(this)
    }
}

// endregion --------------------- Application --------------------------------

// region --------------------- Storage --------------------------------

enum class RoutineState {
    CURRENT, BACKLOG
}

@Entity
data class Routine(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    @ColumnInfo var label: String,
    @ColumnInfo var progress: Int = 0,
    @ColumnInfo var state: RoutineState = RoutineState.CURRENT,
    @ColumnInfo var finishedToday: Boolean = false,

    var currentScore: Float = 0f) {

    //  fully done items still worth a bit
    val lackingProgress: Int
        get() = PROGRESS_FULL - progress + 1

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
    fun getAll(): List<Score>

    @Query("SELECT * FROM score")
    fun subscribeAll(): Observable<List<Score>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(score: Score)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scores: List<Score>)

    @Query("DELETE FROM score")
    fun deleteAll()
}

class EverydayConverters {
    @TypeConverter
    fun fromRoutineState(state: RoutineState) = state.ordinal

    @TypeConverter
    fun toRoutineState(state: Int) = RoutineState.values()[state]
}

@Database(entities = [Routine::class, Score::class], version = 8)
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
    val current: MutableList<Routine>,
    val backlog: MutableList<Routine>
)

private fun sortRoutinesByState(routines: List<Routine>): SortedRoutines {
    val backlog = mutableListOf<Routine>()
    val current = mutableListOf<Routine>()
    for (routine in routines) {
        when (routine.state) {
            RoutineState.CURRENT -> current.add(routine)
            RoutineState.BACKLOG -> backlog.add(routine)
        }
    }
    return SortedRoutines(current, backlog)
}

class EverydayViewModel : ViewModel() {
    private val routinesDao by kodein.instance<RoutineDao>()
    private val scoreDao by kodein.instance<ScoreDao>()
    private val appContext by kodein.instance<Context>()

    private val exportedRoutines = File(appContext.getExternalFilesDir(null), "everyday_routines")
    private val exportedScores = File(appContext.getExternalFilesDir(null), "everyday_scores")

    private val disposable = CompositeDisposable()

    val currentRoutines = BehaviorSubject.create<List<Routine>>()
    val backlogRoutines = BehaviorSubject.create<List<Routine>>()

    val dailyScores = BehaviorSubject.create<Map<LocalDate, Float>>()

    val score: Observable<Float> = currentRoutines.map { calculateCurrentScore(it) }
    val konfetti: Observable<Float> = score.filter { it > GREAT_SCORE }

    init {
        disposable.add(routinesDao.subscribeAll()
            .subscribeOn(Schedulers.io())
            .subscribe { routines ->
                val (current, backlog) = sortRoutinesByState(routines)
                current.sortBy { it.progress }
                backlog.sortBy { it.progress }
                updateScores(current)
                backlogRoutines.onNext(backlog)
                currentRoutines.onNext(current)
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
                val (current, backlog) = sortRoutinesByState(routines)
                scoreDao.insert(Score(0L, timestamp = timestamp, score = calculateCurrentScore(current)))
                val currIter = current.iterator()
                while (currIter.hasNext()) {
                    val routine = currIter.next()
                    if (routine.finishedToday) {
                        routine.finishedToday = false
                        routine.progress = min(routine.progress + 1, PROGRESS_FULL)
                    } else if (routine.progress > 0 && !todayIsWeekend()) {
                        routine.progress -= 1
                    }
                }
                routinesDao.insertAll(backlog)
                routinesDao.insertAll(current)
            }
        }
    }

    fun addRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routinesDao.insert(routine)
            }
        }
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
    }

    fun pauseRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routine.state = RoutineState.BACKLOG
                routinesDao.insert(routine)
            }
        }
    }

    fun continueRoutine(routine: Routine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                routine.state = RoutineState.CURRENT
                routine.finishedToday = false
                routinesDao.insert(routine)
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val routines = routinesDao.getAll()
                var result = ""
                for (routine in routines) {
                    result += "${routine.label};" +
                            "${routine.progress};" +
                            "${routine.state};" +
                            "${routine.finishedToday}\n"
                }
                exportedRoutines.writeText(result)
                result = ""
                val scores = scoreDao.getAll()
                for (score in scores) {
                    result += "${score.timestamp};${score.score}\n"
                }
                exportedScores.writeText(result)
            }
        }
    }

    fun import() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val routines = mutableListOf<Routine>()
                var text = exportedRoutines.readText()
                var lines = text.split(patternNewLine)
                for (line in lines) {
                    if (line.isBlank()) {
                        continue
                    }
                    val split = line.split(patternCsv)
                    check(split.size == 4) { "Wtf? $line" }
                    routines.add(Routine(
                        label = split[0],
                        progress = split[1].toInt(),
                        state = RoutineState.valueOf(split[2]),
                        finishedToday = split[3].toBoolean()))
                }
                routinesDao.deleteAll()
                routinesDao.insertAll(routines)
                val scores = mutableListOf<Score>()
                text = exportedScores.readText()
                lines = text.split(patternNewLine)
                for (line in lines) {
                    if (line.isBlank()) {
                        continue
                    }
                    val split = line.split(patternCsv)
                    check(split.size == 2) { "Wtf? $line" }
                    scores.add(Score(timestamp = split[0].toLong(), score = split[1].toFloat()))
                }
                scoreDao.deleteAll()
                scoreDao.insertAll(scores)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(EverydayViewModel::class.java)
        setContentView(R.layout.activity_main)
        ButterKnife.bind(this)
        createTabs()
        subscribeKonfetti()
        //createNotificationChannel(this)
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

fun Float.toColor() = when {
    this > GREAT_SCORE -> Color.parseColor("#0DE320")
    this > NORMAL_SCORE -> Color.parseColor("#EAC108")
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

    @BindView(R.id.add)
    lateinit var addButton: Button

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
        bootstrapAdd()
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
                scoreTextView.setTextColor(score.toColor())
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
    }

    private fun bootstrapAdd() {
        addButton.setOnClickListener {
            showAddDialog(context!!)
        }
    }

    private fun bootstrapExportImport() {
        exportButton.setOnClickListener {
            viewModel.export()
        }
        exportButton.visibility = if (IS_DEBUG) View.VISIBLE else View.GONE
        importButton.setOnClickListener {
            viewModel.import()
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
                            calendarView.setup(firstMonth, lastMonth, DayOfWeek.MONDAY)
                            calendarView.animateVisible()
                            calendarView.scrollToDate(LocalDate.now())
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
    private val textView: TextView = view.dayText

    fun bind(day: CalendarDay, score: Float?) {
        textView.text = day.date.dayOfMonth.toString()
        if (day.owner == DayOwner.THIS_MONTH) {
            if (day.date == LocalDate.now()) {
                textView.setTextColor(Color.BLACK)
                textView.setTypeface(null, Typeface.BOLD);
            } else if (score != null && score > NORMAL_SCORE) {
                textView.setTextColor(score.toColor())
            }
        } else {
            textView.isEnabled = false
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
    private val monthText: TextView = view.monthText

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
    pauseButton.visibility = if (routine.state != RoutineState.BACKLOG) View.VISIBLE else View.GONE
    continueButton.setOnClickListener {
        dialog.dismiss()
        viewModel.continueRoutine(routine)
    }
    continueButton.visibility = if (routine.state == RoutineState.BACKLOG) View.VISIBLE else View.GONE
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
        labelTextView.isEnabled = routine.state != RoutineState.BACKLOG
        if ((routine.state != RoutineState.BACKLOG) &&
            (routine.progress != 0 || routine.finishedToday)) {
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

/*private fun updateReminderAlarm(context: Context) {
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
}*/

// endregion --------------------- Reminder --------------------------------
