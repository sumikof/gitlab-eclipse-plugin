package com.gitlab.eclipse.quickchat

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.jdt.internal.ui.text.SimpleJavaSourceViewerConfiguration
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.TextSelection
import org.eclipse.jface.text.source.SourceViewer
import org.eclipse.jface.text.source.SourceViewerConfiguration
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyAdapter
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.*
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor
import org.eclipse.jdt.ui.text.JavaTextTools
import org.eclipse.jface.text.ITextViewer

class OpenQuickChatActionHandler : AbstractHandler() {
    companion object {
        val COMMANDS = listOf(
            "/explain",
            "/fix",
            "/test",
            "/refactor"
        )
    }

    private val chatApi = ChatAPI()

    override fun execute(event: ExecutionEvent) {
        val editor = HandlerUtil.getActiveEditor(event) as? ITextEditor
            ?: return

        val textViewer = editor.getAdapter(ITextViewer::class.java)
            ?: return

        val textWidget = textViewer.textWidget
            ?: return

        val editorText = editor.getAdapter(Control::class.java) as? StyledText
            ?: return

        val selection = editor.selectionProvider.selection as? TextSelection
            ?: return

        val quickChatYPosition = editorText.lineHeight * (selection.startLine + 1)
        val composite = Composite(editorText, SWT.NONE).apply {
            layout = GridLayout(1, false).apply {
                marginWidth = 0
                marginHeight = 0
                horizontalSpacing = 2
                verticalSpacing = 2
            }

            layoutData = GridData().apply {
                widthHint = 700
            }

            setLocation(0, quickChatYPosition)
        }

        Label(composite, SWT.NONE).apply {
            text = "Duo Quick Chat"
            font = Font(Display.getCurrent(), "Arial", 13, SWT.NORMAL)
            layoutData = GridData(GridData.FILL_HORIZONTAL).apply { horizontalSpan = 2 }
        }

        Label(composite, SWT.SEPARATOR or SWT.SHADOW_OUT or SWT.HORIZONTAL).apply {
            layoutData = GridData(GridData.FILL or GridData.CENTER).apply {
                verticalIndent = 2
                widthHint = 660
            }
        }

        val document = Document().apply {
            set("public class Example {\n\tpublic static void main(String[] args) {\n\t\t// Your code here\n\t}\n}")
        }

        // for any text
//       val sourceViewerConfiguration = TextSourceViewerConfiguration()

        val preferenceStore = JavaPlugin.getDefault().preferenceStore
        val javaTextTools = JavaTextTools(JavaPlugin.getDefault().preferenceStore)

        val sourceViewerConfiguration: SourceViewerConfiguration = SimpleJavaSourceViewerConfiguration(
            javaTextTools.colorManager,
            preferenceStore,
            null,
            null,
            false
        )

        SourceViewer(composite, null, SWT.V_SCROLL or SWT.H_SCROLL).apply {
            configure(sourceViewerConfiguration)
            this.document = document
            this.isEditable = false
            this.control.layoutData = GridData(GridData.FILL_HORIZONTAL).apply {
                widthHint = 580
            }
        }

        val inputComposite = Composite(composite, SWT.FILL).apply {
            layout = GridLayout(2, false).apply {
                marginWidth = 0
                marginHeight = 0
                horizontalSpacing = 2
            }
        }

        val inputField = StyledText(inputComposite, SWT.SINGLE or SWT.BORDER).apply {
            layoutData = GridData(GridData.FILL_HORIZONTAL).apply {
                widthHint = 580
            }
        }

        Button(inputComposite, SWT.PUSH).apply {
            text = "Send"
            layoutData = GridData(SWT.END, SWT.CENTER, false, false).apply {
                widthHint = 80
            }
        }

        val commandList = List(composite, SWT.SINGLE and SWT.BORDER).apply {
            layoutData = GridData().apply {
                widthHint = 200
                heightHint = itemHeight * COMMANDS.size + 4
                exclude = true
            }

            isVisible = false
        }

        inputField.addModifyListener { _ ->
            val currentText = inputField.text?.takeIf { it.isNotBlank() } ?: return@addModifyListener

            val commandsMatchingText =
                COMMANDS.filter { it.startsWith(currentText) && it != currentText }.toTypedArray()
            if (commandsMatchingText.isEmpty()) {
                commandList.isVisible = false
                (commandList.layoutData as GridData).exclude = true
            } else {
                commandList.setItems(*commandsMatchingText)
                commandList.setSelection(0)
                commandList.isVisible = true
                (commandList.layoutData as GridData).exclude = false
            }

            composite.pack(true)
        }

        inputField.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    // handle press arrow down or up
                    if (event.keyCode == SWT.ARROW_DOWN) {
                        if (!commandList.visible) {
                            return
                        }

                        var newIndex = commandList.selectionIndex + 1
                        if (newIndex >= commandList.itemCount) {
                            newIndex = 0
                        }

                        commandList.setSelection(newIndex)
                        event.doit = false
                    }

                    // We need to manually implement the 'command-a' shortcut (select all)
                    if (event.stateMask == SWT.COMMAND && event.keyCode == 'a'.code) {
                        inputField.selectAll()
                        event.doit = false
                    }
                }
            }
        )

        inputField.addTraverseListener { event ->
            if (event.detail == SWT.TRAVERSE_RETURN) {
                if (commandList.visible) {
                    inputField.text = commandList.getItem(commandList.selectionIndex)
                    inputField.caretOffset = inputField.text.length

                    inputField.setFocus()
                }
            }
        }

        composite.pack()
        inputField.setFocus()

        composite.addPaintListener {
            textWidget.setLineVerticalIndent(
                selection.startLine + 1,
                composite.computeSize(SWT.DEFAULT, SWT.DEFAULT).y
            )
        }

        textWidget.redraw()
    }
}