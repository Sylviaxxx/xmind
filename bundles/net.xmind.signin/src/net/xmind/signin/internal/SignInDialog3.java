package net.xmind.signin.internal;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.codec.binary.Base64;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.DialogPage;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Hyperlink;
import org.xmind.core.net.IDataStore;
import org.xmind.core.net.internal.XMindNetRequest;
import org.xmind.ui.resources.FontUtils;

import net.xmind.signin.IButtonCreator;
import net.xmind.signin.ISignInDialogExtension;
import net.xmind.signin.ISignInDialogExtension2;
import net.xmind.signin.XMindNet;

public class SignInDialog3 extends TitleAreaDialog
        implements IJobChangeListener, IButtonCreator {

    private static class InternalSignInJob extends Job {

        private String user;

        private String password;

        private XMindNetRequest request = null;

        private IDataStore data;

        public InternalSignInJob(String user, String passwrod) {
            super("Sign in to XMind.net"); //$NON-NLS-1$
            setSystem(true);
            this.user = user;
            this.password = passwrod;
        }

        public IDataStore getData() {
            return data;
        }

        @Override
        protected void canceling() {
            if (request != null) {
                request.abort();
            }
            super.canceling();
        }

        private String hash(String password) {
            MessageDigest md5;
            try {
                try {
                    // Try BouncyCastle:
                    md5 = MessageDigest.getInstance("MD5", "BC"); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (NoSuchProviderException e) {
                    md5 = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
                }
            } catch (NoSuchAlgorithmException e) {
                return null;
            }
            Base64 encoder = new Base64();
            try {
                return new String(
                        encoder.encode(md5.digest(password.getBytes())),
                        "UTF-8"); //$NON-NLS-1$
            } catch (UnsupportedEncodingException e) {
                return null;
            }
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            if (monitor.isCanceled())
                return Status.CANCEL_STATUS;

            request = new XMindNetRequest(true);
            request.path("/_res/token/%s", user); //$NON-NLS-1$
            request.addParameter("user", user); //$NON-NLS-1$
            String pwdhash = hash(password);
            if (pwdhash != null) {
                request.addParameter("pwd", pwdhash); //$NON-NLS-1$
            } else {
                request.addParameter("password", password); //$NON-NLS-1$
            }
//            request.addParameter("remember", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            request.post();

            if (monitor.isCanceled() || request.isAborted())
                return Status.CANCEL_STATUS;

            int code = request.getStatusCode();
            IDataStore data = request.getData();
            if (code == XMindNetRequest.HTTP_OK) {
                if (data != null) {
                    this.data = data;
                    return Status.OK_STATUS;
                }
                return error(code,
                        Messages.SignInDialog_ApplicationError_message,
                        request.getError());
            } else if (code == XMindNetRequest.HTTP_ERROR) {
                return error(code, Messages.SignInDialog_NetworkError_message,
                        request.getError());
            } else if (code >= 400 && code < 500) {
                try {
                    // Self throw-catch so that we have the stack info.
                    throw new IOException(request.getResponseText());
                } catch (IOException err) {
                    return error(code,
                            Messages.SignInDialog_RequestError_message, err);
                }
            } else {
                try {
                    // Self throw-catch so that we have the stack info.
                    throw new IOException(request.getResponseText());
                } catch (IOException err) {
                    return error(code,
                            Messages.SignInDialog_ServerError_message, err);
                }
            }
        }

        private static IStatus error(int code, String message,
                Throwable exception) {
            return new Status(IStatus.WARNING, Activator.PLUGIN_ID, code,
                    message, exception);
        }

    }

    private boolean sheet;

    private ISignInDialogExtension extension;

    private Text nameField;

    private Text passwordField;

    private Properties data;

    private Job signInJob;

    private List<Integer> buttonIds = new ArrayList<Integer>();

    private Image titleImage;

    private String message;

    public SignInDialog3(Shell parentShell) {
        this(parentShell, null, null, false, null);
    }

    public SignInDialog3(Shell parentShell, String message,
            ISignInDialogExtension extension) {
        this(parentShell, message, extension, false, null);
    }

    public SignInDialog3(Shell parentShell, String message,
            ISignInDialogExtension extension, boolean sheet, Properties data) {
        super(parentShell);
        this.message = message == null ? Messages.SignInDialog_message
                : message;
        this.extension = extension;
        this.sheet = sheet;
        this.data = data == null ? new Properties() : data;
        if (sheet) {
            setShellStyle(getShellStyle() | SWT.SHEET);
        }
        setBlockOnOpen(true);
    }

    protected Control createContents(Composite parent) {
        Control contents = super.createContents(parent);
        updateButtons();
        return contents;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite composite = (Composite) super.createDialogArea(parent);

//        createTitleArea(composite);

        createFormArea(composite);

        return composite;
    }

    private void createFormArea(Composite parent) {
        Composite form = new Composite(parent, SWT.NONE);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gridLayout = new GridLayout(2, false);
        gridLayout.marginWidth = 30;
        gridLayout.marginHeight = 25;
        gridLayout.marginBottom = 15;
        gridLayout.verticalSpacing = 12;
        gridLayout.horizontalSpacing = 7;
        form.setLayout(gridLayout);

        // Row 1:
        createNameLabel(form);
        createNameField(form);

        // Row 2:
        createPasswordLabel(form);
        createPasswordField(form);

        // Row 3:
        Label emptyPlaceholder = new Label(form, SWT.NONE);
        emptyPlaceholder
                .setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

        Control forgotButton = createForgotPasswordButton(form);
        ((GridData) forgotButton.getLayoutData()).verticalIndent = -10;
    }

    private void createNameLabel(Composite parent) {
        Label label = new Label(parent, SWT.NONE);
        label.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        label.setText(Messages.SignInDialog_NameField_text);
        label.setFont(FontUtils.getBold(JFaceResources.DEFAULT_FONT));
    }

    private void createNameField(Composite parent) {
        nameField = new Text(parent, SWT.BORDER | SWT.SINGLE);
        nameField
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ((GridData) nameField.getLayoutData()).widthHint = 160;
        nameField.setText(data.getProperty(XMindNetAccount.USER, "")); //$NON-NLS-1$
        nameField.addListener(SWT.Modify, new Listener() {
            public void handleEvent(Event event) {
                data.setProperty(XMindNetAccount.USER, nameField.getText());
                setErrorMessage(null);
                updateButtons();
            }
        });
        nameField.addListener(SWT.FocusIn, new Listener() {
            public void handleEvent(Event event) {
                nameField.selectAll();
            }
        });
    }

    private Control createSignUpButton(Composite parent) {
        Hyperlink signupLink = new Hyperlink(parent, SWT.NONE);

        signupLink.setLayoutData(
                new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        signupLink.setForeground(
                signupLink.getDisplay().getSystemColor(SWT.COLOR_BLUE));
        signupLink.setUnderlined(true);

        signupLink.setText(Messages.SignInDialog_NotMember_text);

        signupLink.addHyperlinkListener(new HyperlinkAdapter() {
            @Override
            public void linkActivated(HyperlinkEvent e) {
                XMindNet.gotoURL(true, Activator.URL_SIGN_UP);
            }
        });

        return signupLink;
    }

    private void createPasswordLabel(Composite parent) {
        Label label = new Label(parent, SWT.NONE);
        label.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        label.setText(Messages.SignInDialog_PasswordField_text);
        label.setFont(FontUtils.getBold(JFaceResources.DEFAULT_FONT));
    }

    private void createPasswordField(Composite parent) {
        passwordField = new Text(parent,
                SWT.BORDER | SWT.SINGLE | SWT.PASSWORD);
        passwordField
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ((GridData) passwordField.getLayoutData()).widthHint = 160;
        passwordField.addListener(SWT.Modify, new Listener() {
            public void handleEvent(Event event) {
                setErrorMessage(null);
                updateButtons();
            }
        });
        passwordField.addListener(SWT.FocusIn, new Listener() {
            public void handleEvent(Event event) {
                passwordField.selectAll();
            }
        });
    }

    private Control createForgotPasswordButton(Composite parent) {
        Hyperlink signupLink = new Hyperlink(parent, SWT.NONE);

        signupLink.setLayoutData(
                new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        signupLink.setForeground(
                signupLink.getDisplay().getSystemColor(SWT.COLOR_BLUE));

        signupLink.setText(Messages.SignInDialog_ForgotPassword_text);

        signupLink.addHyperlinkListener(new HyperlinkAdapter() {
            @Override
            public void linkActivated(HyperlinkEvent e) {
                XMindNet.gotoURL(true, Activator.URL_FORGOT_PASSWORD);
            }
        });

        return signupLink;
    }

    protected Control createButtonBar(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        GridLayout gridLayout = new GridLayout(
                hasButtonBarContributor() ? 1 : 2, false);
        gridLayout.marginWidth = convertHorizontalDLUsToPixels(
                IDialogConstants.HORIZONTAL_MARGIN);
        gridLayout.marginHeight = 0;
        gridLayout.marginBottom = convertVerticalDLUsToPixels(
                IDialogConstants.VERTICAL_MARGIN);
        gridLayout.verticalSpacing = convertVerticalDLUsToPixels(
                IDialogConstants.VERTICAL_SPACING);
        gridLayout.horizontalSpacing = convertHorizontalDLUsToPixels(
                IDialogConstants.HORIZONTAL_SPACING);
        composite.setLayout(gridLayout);

        createExpandedArea(composite);

        Composite buttonBar = new Composite(composite, SWT.NONE);
        // create a layout with spacing and margins appropriate for the font
        // size.
        GridLayout layout = new GridLayout();
        layout.numColumns = 0; // this is incremented by createButton
        layout.makeColumnsEqualWidth = false;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = convertHorizontalDLUsToPixels(
                IDialogConstants.HORIZONTAL_SPACING);
        layout.verticalSpacing = convertVerticalDLUsToPixels(
                IDialogConstants.VERTICAL_SPACING);
        buttonBar.setLayout(layout);
        buttonBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, true));
        buttonBar.setFont(parent.getFont());

        // Add the buttons to the button bar.
        createButtonsForButtonBar(buttonBar);
        return buttonBar;
    }

    private void createExpandedArea(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        composite
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gridLayout = new GridLayout(1, false);
        gridLayout.marginWidth = 0;
        gridLayout.marginHeight = 0;
        gridLayout.verticalSpacing = convertVerticalDLUsToPixels(
                IDialogConstants.VERTICAL_SPACING);
        gridLayout.horizontalSpacing = convertHorizontalDLUsToPixels(
                IDialogConstants.HORIZONTAL_SPACING);
        composite.setLayout(gridLayout);

        createSignUpButton(composite);
        //createRememberCheck(composite);
        if (extension != null) {
            createExtensionControls(composite);
        }
    }

    private boolean hasButtonBarContributor() {
        return extension instanceof ISignInDialogExtension2;
    }

    private void createExtensionControls(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        extension.contributeToOptions(this, composite);
        if (composite.getChildren().length == 0) {
            composite.dispose();
        }
    }

    @Override
    public void create() {
        super.create();
        nameField.setFocus();
        setTitle(Messages.SignIn_text);
        if (titleImage == null)
            titleImage = XMindNet.createBannerLogo();
        if (titleImage != null)
            setTitleImage(titleImage);
        setMessage(message);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * net.xmind.signin.IButtonCreator#doCreateButton(org.eclipse.swt.widgets
     * .Composite, int, java.lang.String)
     */
    public Button doCreateButton(Composite parent, int id, String label) {
        buttonIds.add(Integer.valueOf(id));
        return createButton(parent, id, label, false);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID,
                Messages.SignInDialog_SignIn_text, true);
        if (sheet) {
            createButton(parent, IDialogConstants.CANCEL_ID,
                    IDialogConstants.CANCEL_LABEL, false);
        }
        if (extension instanceof ISignInDialogExtension2) {
            ((ISignInDialogExtension2) extension).contributeToButtonBar(this,
                    parent, this);
        }
    }

    protected void configureShell(final Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.SignInDialog_title);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jface.dialogs.Dialog#buttonPressed(int)
     */
    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId != IDialogConstants.OK_ID
                && buttonId != IDialogConstants.CANCEL_ID) {
            if (extension instanceof ISignInDialogExtension2) {
                ((ISignInDialogExtension2) extension).handleButtonPressed(this,
                        buttonId);
                return;
            }
        }
        super.buttonPressed(buttonId);
    }

    private void changeButton(int buttonId, boolean enabled, String withLabel) {
        Button button = getButton(buttonId);
        if (button != null && !button.isDisposed()) {
            if (withLabel != null)
                button.setText(withLabel);
            button.setEnabled(enabled);
        }
    }

    private void updateButtons() {
        boolean hasName = nameField != null && !nameField.isDisposed()
                && !"".equals(nameField.getText()); //$NON-NLS-1$
        boolean hasPassword = passwordField != null
                && !passwordField.isDisposed()
                && !"".equals(passwordField.getText()); //$NON-NLS-1$
        setOKEnabled(hasName && hasPassword);
    }

    private void setOKEnabled(boolean enabled) {
        Button button = getButton(IDialogConstants.OK_ID);
        if (button != null && !button.isDisposed()) {
            button.setEnabled(enabled);
        }
    }

    @Override
    protected void okPressed() {
        changeButton(IDialogConstants.OK_ID, false,
                Messages.SignInDialog_SigningIn_text);
        for (Integer buttonId : buttonIds) {
            changeButton(buttonId.intValue(), false, null);
        }

        startJob();
    }

    @Override
    public boolean close() {
        setReturnCode(CANCEL);
        if (titleImage != null && !titleImage.isDisposed())
            titleImage.dispose();
        return doClose();
    }

    private boolean doClose() {
        boolean closed = super.close();
        if (closed) {
            stopJob();
        }
        return closed;
    }

    private void startJob() {
        stopJob();
        InternalSignInJob job = new InternalSignInJob(nameField.getText(),
                passwordField.getText());
        job.addJobChangeListener(this);
        job.schedule();
        this.signInJob = job;
    }

    private void stopJob() {
        Job job = this.signInJob;
        if (job != null) {
            job.removeJobChangeListener(this);
            job.cancel();
            job = null;
        }
    }

    public String getUserID() {
        return data == null ? null : data.getProperty(XMindNetAccount.USER);
    }

    public String getToken() {
        return data == null ? null : data.getProperty(XMindNetAccount.TOKEN);
    }

    public Properties getData() {
        return data;
    }

    public void aboutToRun(IJobChangeEvent event) {
    }

    public void awake(IJobChangeEvent event) {
    }

    public void done(IJobChangeEvent event) {
        Job job = event.getJob();
        job.removeJobChangeListener(this);
        if (job == signInJob) {
            signInJob = null;
            final IStatus result = event.getResult();
            if (result.getSeverity() == IStatus.OK) {
                IDataStore resultData = ((InternalSignInJob) job).getData();
                data.putAll(resultData.toMap());
                getShell().getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        setReturnCode(OK);
                        doClose();
                    }
                });
            } else {
                getShell().getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        setErrorMessage(result.getMessage());
                        changeButton(IDialogConstants.OK_ID, true,
                                Messages.SignInDialog_SignIn_text);
                        for (Integer buttonId : buttonIds) {
                            changeButton(buttonId.intValue(), true, null);
                        }
                    }
                });
            }
        }
    }

    public void running(IJobChangeEvent event) {
    }

    public void scheduled(IJobChangeEvent event) {
    }

    public void sleeping(IJobChangeEvent event) {
    }

    @Override
    public void setErrorMessage(String errorMessage) {
        if (errorMessage != null)
            setMessage(errorMessage, DialogPage.WARNING);
    }

}
