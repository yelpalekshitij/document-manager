package app.vaultdoc.vaultdoc_mobile

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridges Flutter passkey calls to AndroidX Credential Manager. Each Dart call is a single
 * method invocation; the result is the raw W3C-shape JSON our backend already expects.
 *
 * Error mapping (FlutterError code → Dart exception):
 *   - CANCELLED                — user dismissed the bottom sheet
 *   - NO_CREDENTIAL            — no matching credential available
 *   - INTERRUPTED              — transient OS interruption; UI should suggest retry
 *   - PROVIDER_CONFIGURATION   — Google Password Manager / play services unavailable
 *   - UNSUPPORTED              — SDK below 28 or Credential Manager not on the device
 *   - UNKNOWN                  — fall-through for unexpected platform exceptions
 */
class PasskeyMethodChannel private constructor(private val activity: Activity) :
    MethodChannel.MethodCallHandler {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val credentialManager by lazy { CredentialManager.create(activity) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isSupported" -> result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

            "createCredential" -> {
                val requestJson = call.argument<String>("requestJson")
                if (requestJson.isNullOrBlank()) {
                    result.error("UNKNOWN", "requestJson is required", null)
                    return
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    result.error("UNSUPPORTED", "Credential Manager requires Android 9 (API 28).", null)
                    return
                }
                scope.launch { createCredential(requestJson, result) }
            }

            "getCredential" -> {
                val requestJson = call.argument<String>("requestJson")
                if (requestJson.isNullOrBlank()) {
                    result.error("UNKNOWN", "requestJson is required", null)
                    return
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    result.error("UNSUPPORTED", "Credential Manager requires Android 9 (API 28).", null)
                    return
                }
                scope.launch { getCredential(requestJson, result) }
            }

            else -> result.notImplemented()
        }
    }

    private suspend fun createCredential(requestJson: String, result: MethodChannel.Result) {
        try {
            val request = CreatePublicKeyCredentialRequest(requestJson)
            val response = credentialManager.createCredential(activity, request)
            val pk = response as CreatePublicKeyCredentialResponse
            result.success(pk.registrationResponseJson)
        } catch (e: CreateCredentialCancellationException) {
            result.error("CANCELLED", "User dismissed the passkey prompt.", null)
        } catch (e: CreateCredentialInterruptedException) {
            result.error("INTERRUPTED", e.message ?: "Interrupted", null)
        } catch (e: CreateCredentialNoCreateOptionException) {
            result.error("NO_CREDENTIAL", e.message ?: "No create option offered", null)
        } catch (e: CreateCredentialProviderConfigurationException) {
            result.error("PROVIDER_CONFIGURATION", e.message ?: "No credential provider available", null)
        } catch (e: CreateCredentialException) {
            result.error("UNKNOWN", e.javaClass.simpleName + ": " + (e.message ?: ""), null)
        } catch (e: Exception) {
            result.error("UNKNOWN", e.javaClass.simpleName + ": " + (e.message ?: ""), null)
        }
    }

    private suspend fun getCredential(requestJson: String, result: MethodChannel.Result) {
        try {
            val option = GetPublicKeyCredentialOption(requestJson)
            val request = GetCredentialRequest(listOf(option))
            val response: GetCredentialResponse = credentialManager.getCredential(activity, request)
            when (val credential = response.credential) {
                is PublicKeyCredential -> result.success(credential.authenticationResponseJson)
                else -> result.error("UNKNOWN", "Unexpected credential type: " + credential.javaClass.simpleName, null)
            }
        } catch (e: NoCredentialException) {
            result.error("NO_CREDENTIAL", e.message ?: "No matching credential", null)
        } catch (e: GetCredentialCancellationException) {
            result.error("CANCELLED", "User dismissed the passkey prompt.", null)
        } catch (e: GetCredentialInterruptedException) {
            result.error("INTERRUPTED", e.message ?: "Interrupted", null)
        } catch (e: GetCredentialException) {
            result.error("UNKNOWN", e.javaClass.simpleName + ": " + (e.message ?: ""), null)
        } catch (e: Exception) {
            result.error("UNKNOWN", e.javaClass.simpleName + ": " + (e.message ?: ""), null)
        }
    }

    companion object {
        private const val CHANNEL_NAME = "app.vaultdoc/passkey"

        fun register(activity: Activity, flutterEngine: FlutterEngine) {
            val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
            channel.setMethodCallHandler(PasskeyMethodChannel(activity))
        }
    }
}
