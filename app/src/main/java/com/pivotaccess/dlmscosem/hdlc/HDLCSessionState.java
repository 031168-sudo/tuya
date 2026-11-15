package com.pivotaccess.dlmscosem.hdlc;

/**
 * States of the HDLC session state machine.
 *
 * <pre>
 *            open()
 *   CLOSED ─────────► CONNECTING
 *                          │
 *                    UA received
 *                          │
 *                          ▼
 *                      CONNECTED ◄──────────────────┐
 *                          │                        │
 *                   sendAndReceive()         I-frame exchange
 *                          │                        │
 *                          └────────────────────────┘
 *                          │
 *                       close()
 *                          │
 *                          ▼
 *                    DISCONNECTING
 *                          │
 *                    UA / timeout
 *                          │
 *                          ▼
 *                        CLOSED
 * </pre>
 */
public enum HDLCSessionState {

    /** No link established. Initial and final state. */
    CLOSED,

    /** SNRM sent, waiting for UA from meter. */
    CONNECTING,

    /** Link established — I-frames can be exchanged. */
    CONNECTED,

    /** DISC sent, waiting for UA or timeout. */
    DISCONNECTING
}