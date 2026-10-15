package com.wireless.audiostreamer.network

object CtrlProtocol {
    const val PORT = 45460
    const val T_CTRL = 9

    const val CAP_MIC = 1
    const val CAP_CAM_FRONT = 2
    const val CAP_CAM_BACK = 3
    const val CAP_STORAGE = 4

    const val M_REQUEST = 1
    const val M_ACCEPT = 2
    const val M_DECLINE = 3
    const val M_STOP = 4
    const val M_MIC_FRAME = 5
    const val M_CAM_FRAME = 6
    const val M_FS_LIST_REQ = 7
    const val M_FS_LIST_RESP = 8
    const val M_FS_GET_REQ = 9
    const val M_FS_META = 10
    const val M_FS_CHUNK = 11
    const val M_FS_DONE = 12
    const val M_FS_ERROR = 13
}
