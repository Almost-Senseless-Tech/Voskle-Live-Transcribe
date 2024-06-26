package tech.almost_senseless.voskle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tech.almost_senseless.voskle.data.Languages
import tech.almost_senseless.voskle.data.UserPreferences
import tech.almost_senseless.voskle.data.UserPreferencesRepository
import tech.almost_senseless.voskle.ui.customComposables.ConnectionErrorDialog
import tech.almost_senseless.voskle.ui.customComposables.DataProcessingErrorDialog
import tech.almost_senseless.voskle.ui.customComposables.DownloadConfirmation
import tech.almost_senseless.voskle.ui.customComposables.DownloadSuccessDialog
import tech.almost_senseless.voskle.ui.customComposables.LanguagePicker
import tech.almost_senseless.voskle.ui.customComposables.ModelErrorDialog
import tech.almost_senseless.voskle.ui.customComposables.PermissionDialog
import tech.almost_senseless.voskle.ui.customComposables.RecordAudioPermissionTextProvider
import tech.almost_senseless.voskle.ui.customComposables.SettingsDialog
import tech.almost_senseless.voskle.ui.customComposables.Textarea
import tech.almost_senseless.voskle.ui.customComposables.TimeouteErrorDialog
import tech.almost_senseless.voskle.ui.customComposables.UnexpectedResponseDialog
import tech.almost_senseless.voskle.ui.theme.VoskleLiveTranscribeTheme
import tech.almost_senseless.voskle.util.ObservableInputStream
import tech.almost_senseless.voskle.util.UnzipUtils
import java.io.IOException
import kotlin.io.path.createTempFile


private const val TAG = "MainActivity"
const val PREFERENCES_DATASTORE = "preferences"
val Context.dataStore by preferencesDataStore(
    name = PREFERENCES_DATASTORE
)

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: VLTViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Languages.initialize(applicationContext)

        setContent {
            viewModel = viewModel(
                factory = VLTViewModelFactory(
                    UserPreferencesRepository(
                        applicationContext.dataStore
                    ), applicationContext
                )
            )
            val state = viewModel.state
            val settings = viewModel.settings.collectAsState(initial = UserPreferences())
            VoskleLiveTranscribeTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = true, highContrast = settings.value.highContrast) {
                val recordButtonFocusRequester = FocusRequester()
                val textareaFocusRequester = FocusRequester()

                LaunchedEffect(
                    viewModel.getVoskHub().isModelAvailable(),
                    settings.value.language,
                    state.modelLoaded,
                    state.fetchState,
                    settings.value.generateSpeakerLabels
                ) {
                    if (viewModel.getVoskHub().getModelPath() != settings.value.language.modelPath) {
                        // If the language changed, change the model path referenced in VoskHub
                        viewModel.onAction(VLTAction.SetModelStatus(false))
                        viewModel.getVoskHub().setModelPath(settings.value.language.modelPath)

                        // Dismiss the download confirmation dialog if the model is already downloaded.
                        if (state.displayDownloadConfirmation && viewModel.getVoskHub().isModelAvailable()) {
                            viewModel.onAction(VLTAction.ShowDownloadConfirmation(false))
                        }

                        // Initialize the model if it is already downloaded.
                        if (viewModel.getVoskHub().isModelAvailable()) {
                            viewModel.getVoskHub().initModel(settings.value.generateSpeakerLabels)
                        } else {
                            viewModel.onAction(VLTAction.ShowDownloadConfirmation(true))
                        }
                     }

                    // Initialize the model if it's downloaded but not initialized yet.
                    if (viewModel.getVoskHub().isModelAvailable() && !state.modelLoaded && state.fetchState == FetchState.NO_MODEL) {
                        viewModel.getVoskHub().initModel(settings.value.generateSpeakerLabels)
                    }

                    // A local variable is preferable here, as we don't
                    // want to accidentally initialize the model with the wrong settings.
                    val vosk = viewModel.getVoskHub()

                    // Reinitialize the model if speaker recognition is wanted
                    // but not enabled.
                    if (settings.value.generateSpeakerLabels && state.modelLoaded && state.fetchState == FetchState.READY && !vosk.isUsingSpeakerRecognition()) {
                        vosk.reset()
                        vosk.initModel(true)
                    }

                    if (!settings.value.generateSpeakerLabels && state.modelLoaded && state.fetchState == FetchState.READY && vosk.isUsingSpeakerRecognition()) {
                        vosk.reset()
                        vosk.initModel()
                    }
                }

                LaunchedEffect(
                    settings.value.stopRecordingOnFocusLoss,
                    state.isFocused,
                    isInMultiWindowMode,
                    isInPictureInPictureMode,
                    state.isRecording,
                    state.transcriptFocused
                ) {
                    val windowVisible = isInMultiWindowMode || isInPictureInPictureMode
                    if (!state.isFocused && settings.value.stopRecordingOnFocusLoss && !windowVisible && state.isRecording) {
                        viewModel.getVoskHub().toggleRecording()
                    }

                    /*
                    If the user re-enters the app (window gains focus) while the transcript
                    is the focused element, a crash occurs - possibly only while using TalkBack.
                    To avoid this, we move th focus away from the transcript when the window
                    loses focus.
                     */
                    if (!state.isFocused && state.transcriptFocused) {
                        recordButtonFocusRequester.requestFocus()
                    }
                }

                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted) {
                            if (viewModel.getVoskHub().isModelAvailable()) {
                                viewModel.getVoskHub().toggleRecording()
                            } else {
                                viewModel.getVoskHub().initModel()
                            }
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {

                    when (LocalConfiguration.current.orientation) {
                        Configuration.ORIENTATION_LANDSCAPE -> {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                LanguagePicker(
                                    settings = settings.value,
                                    state = state,
                                    viewModel = viewModel,
                                    modifier = Modifier
                                        .padding(8.dp)
                                )
                                Button(
                                    onClick = {
                                        viewModel.onAction(VLTAction.ShowSettingsDialog(true))
                                    },
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .weight(1f)
                                ) {
                                    Text(text = stringResource(id = R.string.settings))
                                }
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .toggleable(
                                            value = state.keyboardInput,
                                            role = Role.Switch,
                                            onValueChange = {
                                                if (!state.keyboardInput) {
                                                    textareaFocusRequester.requestFocus()
                                                }

                                                if (state.isRecording && !state.keyboardInput) {
                                                    viewModel.onAction(
                                                        VLTAction.SetResumeRecording(
                                                            true
                                                        )
                                                    )
                                                    viewModel
                                                        .getVoskHub()
                                                        .toggleRecording()
                                                }

                                                if (state.keyboardInput && state.resumeRecording) {
                                                    viewModel
                                                        .getVoskHub()
                                                        .toggleRecording()
                                                    viewModel.onAction(
                                                        VLTAction.SetResumeRecording(
                                                            false
                                                        )
                                                    )
                                                }

                                                // Trigger the insertion of a result separator if appropriate
                                                viewModel.onAction(VLTAction.UpdateTranscript(". "))

                                                viewModel.onAction(
                                                    VLTAction.SetKeyboardInput(
                                                        !state.keyboardInput
                                                    )
                                                )
                                            }
                                        ),
                                ) {
                                    Switch(checked = state.keyboardInput, null, modifier = Modifier.padding(horizontal = 8.dp))
                                    Text(text = stringResource(id = R.string.keyboard_input), color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }

                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                LanguagePicker(
                                    settings = settings.value,
                                    state = state,
                                    viewModel = viewModel,
                                    modifier = Modifier
                                        .padding(8.dp)
                                )
                                Button(
                                    onClick = {
                                        viewModel.onAction(VLTAction.ShowSettingsDialog(true))
                                    },
                                    modifier = Modifier
                                        .padding(8.dp)
                                ) {
                                    Text(text = stringResource(id = R.string.settings))
                                }
                                Row(
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .toggleable(
                                            value = state.keyboardInput,
                                            role = Role.Switch,
                                            onValueChange = {
                                                if (!state.keyboardInput) {
                                                    textareaFocusRequester.requestFocus()
                                                }

                                                if (state.isRecording && !state.keyboardInput) {
                                                    viewModel.onAction(
                                                        VLTAction.SetResumeRecording(
                                                            true
                                                        )
                                                    )
                                                    viewModel
                                                        .getVoskHub()
                                                        .toggleRecording()
                                                }

                                                if (state.keyboardInput && state.resumeRecording) {
                                                    viewModel
                                                        .getVoskHub()
                                                        .toggleRecording()
                                                    viewModel.onAction(
                                                        VLTAction.SetResumeRecording(
                                                            false
                                                        )
                                                    )
                                                }

                                                // Trigger the insertion of a result separator if appropriate.
                                                viewModel.onAction(VLTAction.UpdateTranscript(". "))

                                                viewModel.onAction(
                                                    VLTAction.SetKeyboardInput(
                                                        !state.keyboardInput
                                                    )
                                                )
                                            }
                                        ),
                                ) {
                                    Switch(checked = state.keyboardInput, null, modifier = Modifier.padding(horizontal = 8.dp))
                                    Text(text = stringResource(id = R.string.keyboard_input), color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }

                    when (LocalConfiguration.current.orientation) {
                        Configuration.ORIENTATION_PORTRAIT -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                            ) {
                                Textarea(
                                    settings = settings.value,
                                    state = state,
                                    onAction = viewModel::onAction,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            viewModel.onAction(VLTAction.SetTranscriptFocused(it.isFocused))
                                        }
                                        .focusRequester(textareaFocusRequester)
                                        .weight(5f)
                                )

                                Row {
                                    Button(
                                        enabled = viewModel.state.modelLoaded && !state.keyboardInput,
                                        onClick = {
                                            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !state.isRecording) {
                                                viewModel.onAction(
                                                    VLTAction.ShowPermissionsDialog(
                                                        true
                                                    )
                                                )
                                            } else {
                                                viewModel.getVoskHub().toggleRecording()
                                            }
                                        },
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .weight(1f)
                                            .focusRequester(recordButtonFocusRequester)
                                    ) {
                                        val transcribeButtonLabel =
                                            if (viewModel.state.modelLoaded && viewModel.state.isRecording) stringResource(
                                                id = R.string.stop_transcribing
                                            ) else stringResource(id = R.string.start_transcribing)
                                        Text(text = transcribeButtonLabel)
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.onAction(VLTAction.ClearTranscript)
                                        },
                                        enabled = viewModel.state.transcript.isNotEmpty(),
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .weight(1f)
                                    ) {
                                        Text(text = stringResource(id = R.string.clear_transcript))
                                    }

                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .semantics { liveRegion = LiveRegionMode.Assertive }
                                        .weight(1f)
                                        .padding(8.dp)
                                ) {
                                    Text(text = getFetchStateText(state.fetchState), color = MaterialTheme.colorScheme.tertiary)
                                    if (state.modelProcessingProgress != null) {
                                        LinearProgressIndicator(
                                            progress = state.modelProcessingProgress / 100f,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                            ) {
                                Textarea(
                                    settings = settings.value,
                                    state = state,
                                    onAction = viewModel::onAction,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            viewModel.onAction(VLTAction.SetTranscriptFocused(it.isFocused))
                                        }
                                        .focusRequester(textareaFocusRequester)
                                        .weight(8f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .weight(3f)
                                ) {
                                    Button(
                                        enabled = viewModel.state.modelLoaded && !state.keyboardInput,
                                        onClick = {
                                            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !state.isRecording) {
                                                viewModel.onAction(
                                                    VLTAction.ShowPermissionsDialog(
                                                        true
                                                    )
                                                )
                                            } else {
                                                viewModel.getVoskHub().toggleRecording()
                                            }
                                        },
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .weight(1f)
                                            .focusRequester(recordButtonFocusRequester)
                                    ) {
                                        val transcribeButtonLabel =
                                            if (viewModel.state.modelLoaded && viewModel.state.isRecording) stringResource(
                                                id = R.string.stop_transcribing
                                            ) else stringResource(id = R.string.start_transcribing)
                                        Text(text = transcribeButtonLabel)
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.onAction(VLTAction.ClearTranscript)
                                        },
                                        enabled = viewModel.state.transcript.isNotEmpty(),
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .weight(1f)
                                    ) {
                                        Text(text = stringResource(id = R.string.clear_transcript))
                                    }
                                }


                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .semantics { liveRegion = LiveRegionMode.Assertive }
                                        .padding(8.dp)
                                        .weight(1f)
                                ) {
                                    Text(text = getFetchStateText(state.fetchState), color = MaterialTheme.colorScheme.tertiary)
                                    if (state.modelProcessingProgress != null) {
                                        LinearProgressIndicator(
                                            progress = state.modelProcessingProgress / 100f,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }


                    if (state.displaySettingsDialog) {
                        SettingsDialog(
                            settings = settings.value,
                            state = state,
                            contactUs = ::contactUs,
                            onAction = viewModel::onAction
                        )
                    }

                    if (state.displayDownloadConfirmation) {
                        DownloadConfirmation(
                            settings = settings.value,
                            downloadFunction = ::downloadModel,
                            onAction = viewModel::onAction
                        )
                    }

                    if (state.displayDownloadSuccess) {
                        DownloadSuccessDialog(onAction = viewModel::onAction)
                    }

                    if (state.displayPermissionsDialog) {
                        PermissionDialog(
                            permissionTextProvider = RecordAudioPermissionTextProvider(),
                            isPermanentlyDeclined = shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO),
                            onDismiss = {
                                viewModel.onAction(VLTAction.ShowPermissionsDialog(false))
                            },
                            onOkClick = {
                                viewModel.onAction(VLTAction.ShowPermissionsDialog(false))
                                requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                            onGoToAppSettingsClick = { openAppSettings() })
                    }

                    if (state.error != null) {
                        val dialog = when (@Suppress("UNNECESSARY_NOT_NULL_ASSERTION") val err = state.error!!) {
                            is ErrorKind.ConnectionFailed -> {
                                ConnectionErrorDialog(
                                    downloadFunction = ::downloadModel,
                                    message = err.message,
                                    contactUsCallback = ::contactUs,
                                    onAction = viewModel::onAction,
                                    modelPath = settings.value.language.modelPath
                                )
                            }

                            is ErrorKind.UnexpectedResponse -> {
                                UnexpectedResponseDialog(
                                    downloadFunction = ::downloadModel,
                                    message = err.message,
                                    contactUsCallback = ::contactUs,
                                    onAction = viewModel::onAction,
                                    modelPath = settings.value.language.modelPath
                                )
                            }

                            is ErrorKind.DataProcessionFailed -> {
                                DataProcessingErrorDialog(
                                    downloadFunction = ::downloadModel,
                                    message = err.message,
                                    contactUsCallback = ::contactUs,
                                    onAction = viewModel::onAction,
                                    modelPath = settings.value.language.modelPath
                                )
                            }

                            is ErrorKind.TranscriptionTimeout -> {
                                TimeouteErrorDialog(
                                    contactUsCallback = ::contactUs,
                                    onAction = viewModel::onAction
                                )
                            }

                            is ErrorKind.ModelError -> {
                                ModelErrorDialog(
                                    message = err.message,
                                    contactUsCallback = ::contactUs,
                                    onAction = viewModel::onAction
                                )
                            }
                        }
                        dialog.RenderErrorDialog()
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        viewModel.onAction(VLTAction.SetFocusedState(hasFocus))
    }

    private fun contactUs() {
        Log.d(TAG, "contactUs: Function called")
        val subject = "[vlt] Feedback"
        val androidSdkInt = android.os.Build.VERSION.SDK_INT
        val model = android.os.Build.MODEL
        val appVersion = BuildConfig.VERSION_NAME
        val appBuildInt = BuildConfig.VERSION_CODE
        val text = "\n\n-----\n\nApp version: $appVersion ($appBuildInt)\nAndroid version: $androidSdkInt\nModel: $model"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("projects@almost-senseless.tech"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        if (intent.resolveActivity(packageManager) != null) {
            Log.d(TAG, "contactUs: Found email app, launching it.")
            startActivity(intent)
        }
    }

    private fun downloadModel(viewModel: VLTViewModel, modelPath: String) {
        val url = "https://models.vlt.almost-senseless.tech/$modelPath.zip"
        val httpClient = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()
        viewModel.onAction(VLTAction.UpdateFetchState(FetchState.DOWNLOADING))
        httpClient.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                viewModel.onAction(VLTAction.SetError(ErrorKind.ConnectionFailed(e.localizedMessage ?: "")))
                viewModel.onAction(VLTAction.UpdateFetchState(FetchState.NO_MODEL))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        viewModel.onAction(VLTAction.SetError(
                            ErrorKind.UnexpectedResponse("${response.code} (${response.message})")
                        ))
                    } else {
                        try {
                            val externalFilesDir = applicationContext.getExternalFilesDir(null)
                            val dataStream = ObservableInputStream(response.body!!.byteStream()) {
                                val progress = it * 100 / response.body!!.contentLength()
                                viewModel.onAction(VLTAction.UpdateModelProcessingProgress(progress.toFloat()))
                            }
                            val sourceFile = createTempFile().toFile()
                            sourceFile.outputStream().use { output ->
                                dataStream.copyTo(output)
                            }
                            viewModel.onAction(VLTAction.UpdateFetchState(FetchState.UNPACKING))
                            UnzipUtils.unzip(sourceFile, "$externalFilesDir/models")
                            viewModel.onAction(VLTAction.ShowDownloadSuccess(true))
                            viewModel.onAction(VLTAction.UpdateModelProcessingProgress(null))
                            sourceFile.delete()
                        } catch (e: IOException) {
                            viewModel.onAction(VLTAction.SetError(ErrorKind.DataProcessionFailed(
                                if (e.localizedMessage != null) {
                                    "${e.localizedMessage}\n"
                                } else {
                                    ""
                                }
                            )))
                        }
                    }
                    viewModel.onAction(VLTAction.UpdateFetchState(FetchState.NO_MODEL))
                }
            }
        })
    }

    private fun getFetchStateText(fetchState: FetchState): String {
        return when (fetchState) {
            FetchState.DOWNLOADING -> getString(R.string.downloading_state)
            FetchState.UNPACKING -> getString(R.string.unpacking_state)
            FetchState.READY -> getString(R.string.ready_to_transcribe_state)
            FetchState.NO_MODEL -> getString(R.string.no_model_loaded_state)
            FetchState.NONE -> ""
        }
    }
}

fun Activity.openAppSettings() {
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).also(::startActivity)
}