package app.copy

import android.app.Application
import app.copy.data.CopyContainer
import app.copy.data.CopyDependencies

class CopyApplication : Application(), CopyDependencies {
    override val container by lazy { CopyContainer(this) }
}
