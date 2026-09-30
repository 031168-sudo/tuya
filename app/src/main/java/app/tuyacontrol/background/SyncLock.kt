package app.tuyacontrol.background

import kotlinx.coroutines.sync.Mutex

/**
 * Общий замок загрузки истории: приложение и фоновая задача работают в одном процессе,
 * и одновременная загрузка одного журнала дважды прибавила бы расход (данные дописываются).
 */
object SyncLock {
    val mutex = Mutex()
}
