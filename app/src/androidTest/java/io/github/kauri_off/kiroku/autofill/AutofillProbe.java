package io.github.kauri_off.kiroku.autofill;

import android.app.assist.AssistStructure;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Logs every field it is offered and, as a password manager, asks to save any password field; Java runs without Kotlin.
public final class AutofillProbe extends AutofillService {
    private static final String TAG = "AutofillProbe";

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        List<FillContext> contexts = request.getFillContexts();
        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        List<AutofillId> passwords = new ArrayList<>();
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            visit(structure.getWindowNodeAt(i).getRootViewNode(), passwords);
        }
        Log.i(TAG, "fill request from " + structure.getActivityComponent() + ": " + passwords.size() + " password fields");
        FillResponse response = null;
        if (!passwords.isEmpty()) {
            SaveInfo save = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, passwords.toArray(new AutofillId[0])).build();
            response = new FillResponse.Builder().setSaveInfo(save).build();
        }
        callback.onSuccess(response);
    }

    private void visit(AssistStructure.ViewNode node, List<AutofillId> passwords) {
        String[] hints = node.getAutofillHints();
        if (node.getAutofillType() != View.AUTOFILL_TYPE_NONE || hints != null) {
            Log.i(TAG, "field " + node.getAutofillId() + " type=" + node.getAutofillType()
                + " hints=" + (hints == null ? null : Arrays.toString(hints)) + " inputType=" + node.getInputType());
        }
        boolean password = false;
        if (hints != null) {
            for (String hint : hints) password |= hint.toLowerCase().contains(View.AUTOFILL_HINT_PASSWORD);
        }
        int variation = node.getInputType() & InputType.TYPE_MASK_VARIATION;
        password |= variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (password && node.getAutofillId() != null) passwords.add(node.getAutofillId());
        for (int i = 0; i < node.getChildCount(); i++) visit(node.getChildAt(i), passwords);
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        Log.i(TAG, "SAVE REQUEST");
        callback.onSuccess();
    }
}
