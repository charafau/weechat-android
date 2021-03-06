package com.ubergeek42.WeechatAndroid.fragments;

import java.util.Vector;

import android.support.v4.app.Fragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ubergeek42.WeechatAndroid.adapters.ChatLinesAdapter;
import com.ubergeek42.WeechatAndroid.R;
import com.ubergeek42.WeechatAndroid.WeechatActivity;
import com.ubergeek42.WeechatAndroid.service.Buffer;
import com.ubergeek42.WeechatAndroid.service.BufferEye;
import com.ubergeek42.WeechatAndroid.service.BufferList;
import com.ubergeek42.WeechatAndroid.service.RelayService;
import com.ubergeek42.WeechatAndroid.service.RelayServiceBinder;
import com.ubergeek42.WeechatAndroid.utils.CopyPaste;
import com.ubergeek42.weechat.ColorScheme;
import com.ubergeek42.weechat.relay.RelayConnectionHandler;

import static com.ubergeek42.WeechatAndroid.utils.Constants.*;

public class BufferFragment extends Fragment implements BufferEye, OnKeyListener,
        OnClickListener, TextWatcher, RelayConnectionHandler, TextView.OnEditorActionListener {

    private static Logger logger = LoggerFactory.getLogger("BufferFragment");
    final private static boolean DEBUG_TAB_COMPLETE = false;
    final private static boolean DEBUG_LIFECYCLE = false;
    final private static boolean DEBUG_VISIBILITY = false;
    final private static boolean DEBUG_MESSAGES = false;
    final private static boolean DEBUG_CONNECTION = false;
    final private static boolean DEBUG_AUTOSCROLLING = false;

    public final static String LOCAL_PREF_FULL_NAME = "full_name";

    private WeechatActivity activity = null;
    private boolean started = false;

    private ListView uiLines;
    private EditText uiInput;
    private ImageButton uiSend;
    private ImageButton uiTab;

    private String fullName = "…";
    private String shortName = fullName;
    private Buffer buffer;

    private RelayServiceBinder relay;
    private ChatLinesAdapter linesAdapter;
    private SharedPreferences prefs;

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// life cycle
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAttach(Context context) {
        if (DEBUG_LIFECYCLE) logger.warn("{} onAttach(...)", fullName);
        super.onAttach(context);
        this.activity = (WeechatActivity) context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DEBUG_LIFECYCLE) logger.warn("{} onCreate()", fullName);
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        shortName = fullName = getArguments().getString(LOCAL_PREF_FULL_NAME);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (DEBUG_LIFECYCLE) logger.warn("{} onCreateView()", fullName);
        View v = inflater.inflate(R.layout.chatview_main, container, false);

        uiLines = (ListView) v.findViewById(R.id.chatview_lines);
        uiInput = (EditText) v.findViewById(R.id.chatview_input);
        uiSend = (ImageButton) v.findViewById(R.id.chatview_send);
        uiTab = (ImageButton) v.findViewById(R.id.chatview_tab);

        uiSend.setOnClickListener(this);
        uiTab.setOnClickListener(this);
        uiInput.setOnKeyListener(this);            // listen for hardware keyboard
        uiInput.addTextChangedListener(this);      // listen for software keyboard through watching input box text
        uiInput.setOnEditorActionListener(this);   // listen for software keyboard's “send” click. see onEditorAction()

        uiLines.setFocusable(false);
        uiLines.setFocusableInTouchMode(false);

        CopyPaste cp = new CopyPaste(activity, uiInput);
        uiInput.setOnLongClickListener(cp);
        uiLines.setOnItemLongClickListener(cp);

        return v;
    }

    @Override
    public void onStart() {
        if (DEBUG_LIFECYCLE) logger.warn("{} onStart()", fullName);
        super.onStart();
        started = true;
        activity.bind(this);
        //noinspection deprecation
        uiLines.setBackgroundColor(0xFF000000 | ColorScheme.get().defaul[ColorScheme.OPT_BG]);
    }

    @Override
    public void onStop() {
        if (DEBUG_LIFECYCLE) logger.warn("{} onStop()", fullName);
        super.onStop();
        started = false;
        detachFromBuffer();
        relay = null;
        activity.unbind(this);
    }

    @Override
    public void onDetach() {
        if (DEBUG_LIFECYCLE) logger.warn("{} onDetach()", fullName);
        activity = null;
        super.onDetach();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// visibility (set by pager adapter)
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private boolean pagerVisible = false;
    private boolean visible = false;

    /** these are the highlight and private counts that we are supposed to scroll
     ** they are reset after the scroll has been completed */
    private int highlights = 0;

    // this method is using the following:
    // lastVisibleLine      last line that exists in the buffer. NOTE: "visible" here means line is not filtered in weechat
    // readMarkerLine       used for display. it is:
    //     * saved on app shutdown and restored on start
    //     * altered if a buffer has been read in weechat (see BufferList.saveLastReadLine)
    //     * set to the last displayed line when user navigates away from a buffer
    //     * shifted from invisible line to last visible line if buffer is filtered
    private void maybeMoveReadMarker() {
        if (DEBUG_VISIBILITY) logger.warn("{} maybeMoveReadMarker({})", shortName);
        if (buffer != null && buffer.readMarkerLine != buffer.lastVisibleLine) {
            buffer.readMarkerLine = buffer.lastVisibleLine;
            linesAdapter.needMoveLastReadMarker = true;
            onLinesChanged();
        }
    }

    private int privates = 0;

    /** called when visibility of current fragment is (potentially) altered by
     **   * drawer being shown/hidden
     **   * whether buffer is shown in the pager (see MainPagerAdapter)
     **   * availability of buffer & activity
     **   * lifecycle (todo) */
    public void maybeChangeVisibilityState() {
        if (DEBUG_VISIBILITY) logger.warn("{} maybeChangeVisibilityState()", shortName);
        if (activity == null || buffer == null)
            return;

        // see if visibility has changed. if it hasn't, do nothing
        boolean obscured = activity.isPagerNoticeablyObscured();
        boolean visible = started && pagerVisible && !obscured;

        if (this.visible == visible) return;
        this.visible = visible;

        // visibility has changed.
        if (visible) {
            highlights = buffer.highlights;
            privates = (buffer.type == Buffer.PRIVATE) ? buffer.unreads : 0;
        }
        buffer.setWatched(visible);
        scrollToHotLineIfNeeded();

        // move the read marker in weechat (if preferences dictate)
        if (!visible && prefs.getBoolean(PREF_HOTLIST_SYNC, PREF_HOTLIST_SYNC_D)) {
                relay.sendMessage("input " + buffer.fullName + " /buffer set hotlist -1");
                relay.sendMessage("input " + buffer.fullName + " /input set_unread_current_buffer");
        }
    }

    /** called by MainPagerAdapter
     ** tells us that this page is visible, also used to lifecycle calls (must call super) */
    @Override
    public void setUserVisibleHint(boolean visible) {
        if (DEBUG_VISIBILITY) logger.warn("{} setUserVisibleHint({})", fullName, visible);
        super.setUserVisibleHint(visible);
        this.pagerVisible = visible;
        if (!visible) maybeMoveReadMarker();
        maybeChangeVisibilityState();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// fake service connection
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void onServiceConnected(RelayServiceBinder relay) {
        if (DEBUG_LIFECYCLE) logger.warn("{} onServiceConnected()", fullName);
        this.relay = relay;
        boolean online = relay.isConnection(RelayService.BUFFERS_LISTED);
        initUI(online);
        attachToBufferOrClose();
    }

    // should never ever happen
    public void onServiceDisconnected() {
        if (buffer != null) buffer.setBufferEye(null);
        buffer = null;
        relay = null;
    }


    //////////////////////////////////////////////////////////////////////////////////////////////// RelayConnectionHandler stuff

    @Override public void onConnecting() {}
    @Override public void onConnect() {}
    @Override public void onAuthenticated() {}
    @Override public void onAuthenticationFailed() {}
    @Override public void onError(String err, Object extraInfo) {}

    public void onBuffersListed() {
        if (DEBUG_CONNECTION) logger.warn("{} onBuffersListed()", fullName);
        initUI(true);
        attachToBufferOrClose();
    }

    @Override
    public void onDisconnect() {
        if (DEBUG_CONNECTION) logger.warn("{} onDisconnect()", fullName);
        initUI(false);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// the juice
    ////////////////////////////////////////////////////////////////////////////////////////////////

    // there's relay now
    private void attachToBufferOrClose() {
        if (DEBUG_LIFECYCLE) logger.warn("{} attachToBufferOrClose()", fullName);
        relay.addRelayConnectionHandler(this);          // connect/disconnect watcher

        buffer = relay.getBufferByFullName(fullName);
        if (buffer == null) {
            // no buffer? it might happen if:
            //  * the buffer was closed in weechat. if so, close here as well
            //    (post so that closing doesn't get executed on current loop to avoid issues)
            //  * we are not yet connected, e.g., after service shutdown. if so,
            //    wait for onBuffersListed event
            if (relay.isConnection(RelayService.BUFFERS_LISTED)) onBufferClosed();
            return;
        }
        shortName = buffer.shortName;
        buffer.setBufferEye(this);                      // buffer watcher TODO: java.lang.NullPointerException if run in thread ?!?!

        linesAdapter = new ChatLinesAdapter(activity, buffer, uiLines);
        linesAdapter.setFont(prefs.getString(PREF_BUFFER_FONT, PREF_BUFFER_FONT_D));
        linesAdapter.readLinesFromBuffer();

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.updateCutePagerTitleStrip();
                uiLines.setAdapter(linesAdapter);
            }
        });
        maybeChangeVisibilityState();
    }

    // no relay after dis :<
    // buffer might be null if we are closing fragment that is not connected
    private void detachFromBuffer() {
        if (DEBUG_LIFECYCLE) logger.warn("{} detachFromBuffer()", fullName);
        maybeChangeVisibilityState();
        if (relay != null) relay.removeRelayConnectionHandler(this);        // remove connect / disconnect watcher
        if (buffer != null) buffer.setBufferEye(null);                      // remove buffer watcher
        buffer = null;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////// ui

    public void initUI(final boolean online) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uiInput.setFocusable(online);
                uiInput.setFocusableInTouchMode(online);
                uiSend.setVisibility(prefs.getBoolean(PREF_SHOW_SEND, PREF_SHOW_SEND_D) ? View.VISIBLE : View.GONE);
                uiTab.setVisibility(prefs.getBoolean(PREF_SHOW_TAB, PREF_SHOW_TAB_D) ? View.VISIBLE : View.GONE);
                uiSend.setEnabled(online);
                uiTab.setEnabled(online);
                if (!online)
                    activity.hideSoftwareKeyboard();
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// BufferEye stuff
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onLinesChanged() {
        linesAdapter.onLinesChanged();
    }

    @Override
    public void onLinesListed() {
        if (DEBUG_MESSAGES) logger.warn("{} onLinesListed()", fullName);
        scrollToHotLineIfNeeded();
    }

    @Override
    public void onPropertiesChanged() {
        linesAdapter.onPropertiesChanged();
    }

    @Override
    public void onBufferClosed() {
        if (DEBUG_CONNECTION) logger.warn("{} onBufferClosed()", fullName);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                activity.closeBuffer(fullName);
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// scrolling
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** scroll to the first hot line, if possible (that is, first unread line in a private buffer
     **     or the first unread highlight)
     ** can be called multiple times, will only run once
     ** posts to the listview to make sure it's fully completed loading the items
     **     after setting the adapter or updating lines */
    public void scrollToHotLineIfNeeded() {
        if (DEBUG_AUTOSCROLLING) logger.error("{} scrollToHotLineIfNeeded()", shortName);
        if (buffer != null && visible && buffer.holdsAllLines && (highlights > 0 || privates > 0)) {
            uiLines.post(new Runnable() {
                @Override
                public void run() {
                    int count = linesAdapter.getCount(), idx = -2;

                    if (privates > 0) {
                        int p = 0;
                        for (idx = count - 1; idx >= 0; idx--) {
                            Buffer.Line line = (Buffer.Line) linesAdapter.getItem(idx);
                            if (line.type == Buffer.Line.LINE_MESSAGE && ++p == privates) break;
                        }
                    } else if (highlights > 0) {
                        int h = 0;
                        for (idx = count - 1; idx >= 0; idx--) {
                            Buffer.Line line = (Buffer.Line) linesAdapter.getItem(idx);
                            if (line.highlighted && ++h == highlights) break;
                        }
                    }

                    if (idx == -1)
                        Toast.makeText(getActivity(), activity.getString(R.string.autoscroll_no_line), Toast.LENGTH_SHORT).show();
                    else if (idx > 0)
                        uiLines.smoothScrollToPosition(idx);

                    highlights = privates = 0;
                }
            });
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// keyboard / buttons
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** the only OnKeyListener's method
     ** User pressed some key in the input box, check for what it was
     ** NOTE: this only applies to HARDWARE buttons */
    @Override
    public boolean onKey(View v, int keycode, KeyEvent event) {
        if (DEBUG_TAB_COMPLETE) logger.warn("{} onKey(..., {}, ...)", fullName, keycode);
        int action = event.getAction();
        // Enter key sends the message
        return checkSendMessage(keycode, action) ||
                checkVolumeButtonResize(keycode, action) ||
                checkForTabCompletion(keycode, action);

    }

    private boolean checkSendMessage(int keycode, int action) {
        if (keycode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_UP) sendMessage();
            return true;
        }
        return false;
    }

    private boolean checkForTabCompletion(int keycode, int action) {
        if ((keycode == KeyEvent.KEYCODE_TAB || keycode == KeyEvent.KEYCODE_SEARCH) &&
                action == KeyEvent.ACTION_DOWN) {
            tryTabComplete();
            return true;
        }
        return false;
    }

    private boolean checkVolumeButtonResize(int keycode, int action) {
        if (keycode == KeyEvent.KEYCODE_VOLUME_DOWN || keycode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (prefs.getBoolean(PREF_VOLUME_BTN_SIZE, PREF_VOLUME_BTN_SIZE_D)) {
                if (action == KeyEvent.ACTION_UP) {
                    float textSize = Float.parseFloat(prefs.getString(PREF_TEXT_SIZE, PREF_TEXT_SIZE_D));
                    switch (keycode) {
                        case KeyEvent.KEYCODE_VOLUME_UP:
                            if (textSize < 30) textSize += 1;
                            break;
                        case KeyEvent.KEYCODE_VOLUME_DOWN:
                            if (textSize > 5) textSize -= 1;
                            break;
                    }
                    prefs.edit().putString(PREF_TEXT_SIZE, Float.toString(textSize)).commit();
                }
                return true;
            }
        }
        return false;
    }

    /** the only OnClickListener's method
     ** our own send button or tab button pressed */
    @Override
    public void onClick(View v) {
        if (v.getId() == uiSend.getId())
            sendMessage();
        else if (v.getId() == uiTab.getId())
            tryTabComplete();
    }

    /** the only OnEditorActionListener's method
     ** listens to keyboard's “send” press (NOT our button) */
    @Override
    public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            sendMessage();
            return true;
        }
        return false;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////// send message

    /** sends the message if there's anything to send */
    private void sendMessage() {
        String input = uiInput.getText().toString();
        if (input.length() != 0)
            BufferList.addSentMessage(input);
        String[] lines = input.split("\n");
        for (String line : lines) {
            if (line.length() != 0)
                relay.sendMessage("input " + buffer.fullName + " " + line);
        }
        uiInput.setText("");   // this will reset tab completion
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// tab completion
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private boolean tcInProgress;
    private Vector<String> tcMatches;
    private int tcIndex;
    private int tcWordStart;
    private int tcWordEnd;

    /** attempts to perform tab completion on the current input */
    private void tryTabComplete() {
        if (DEBUG_TAB_COMPLETE) logger.warn("tryTabComplete()");
        if (buffer == null) return;

        String txt = uiInput.getText().toString();
        if (!tcInProgress) {
            // find the end of the word to be completed
            // blabla nick|
            tcWordEnd = uiInput.getSelectionStart();
            if (tcWordEnd <= 0)
                return;

            // find the beginning of the word to be completed
            // blabla |nick
            tcWordStart = tcWordEnd;
            while (tcWordStart > 0 && txt.charAt(tcWordStart - 1) != ' ')
                tcWordStart--;

            // get the word to be completed, lowercase
            if (tcWordStart == tcWordEnd)
                return;
            String prefix = txt.substring(tcWordStart, tcWordEnd).toLowerCase();

            // compute a list of possible matches
            // nicks is ordered in last used comes first way, so we just pick whatever comes first
            // if computed list is empty, abort
            tcMatches = new Vector<>();

            for (String nick : buffer.getLastUsedNicksCopy())
                if (nick.toLowerCase().startsWith(prefix))
                    tcMatches.add(nick.trim());
            if (tcMatches.size() == 0)
                return;

            tcIndex = 0;
        } else {
            tcIndex = (tcIndex + 1) % tcMatches.size();
        }

        // get new nickname, adjust the end of the word marker
        // and finally set the text and place the cursor on the end of completed word
        String nick = tcMatches.get(tcIndex);
        if (tcWordStart == 0)
            nick += ": ";
        uiInput.setText(txt.substring(0, tcWordStart) + nick + txt.substring(tcWordEnd));
        tcWordEnd = tcWordStart + nick.length();
        uiInput.setSelection(tcWordEnd);
        // altering text in the input box sets tcInProgress to false,
        // so this is the last thing we do in this function:
        tcInProgress = true;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////// text watcher

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

    /** invalidate tab completion progress on input box text change
     ** tryTabComplete() will set it back if it modified the text causing this function to run */
    @Override
    public void afterTextChanged(Editable s) {
        if (DEBUG_TAB_COMPLETE) logger.warn("{} afterTextChanged(...)", fullName);
        tcInProgress = false;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    public String getShortBufferName() {
        return shortName;
    }
}
