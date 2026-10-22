package app.ti

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPlatformTest {
    @Test
    fun packagedAppRequiresAndroid13AndDoesNotRequestStoragePermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Run validation on Android 13 or newer", Build.VERSION.SDK_INT >= 33)
        assertEquals("Installed APK must require API 33", 33, context.applicationInfo.minSdkVersion)
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val storagePermissions = setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        )
        assertTrue("App-private repositories must not request storage access", permissions.intersect(storagePermissions).isEmpty())
    }
}
