package com.gitlab.eclipse.quickchat

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.TextSelection
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.ScrolledComposite
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyAdapter
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.*
import org.eclipse.swt.widgets.List
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor
import java.util.*
import kotlin.collections.filter
import kotlin.collections.isEmpty
import kotlin.collections.listOf
import kotlin.collections.toTypedArray
import kotlin.math.max


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

        val chatHistoryScroll = ScrolledComposite(composite, SWT.V_SCROLL or SWT.H_SCROLL).apply {
            layoutData = GridData(SWT.FILL, SWT.FILL, true, true).apply {
                heightHint = 300
            }
        }

        val chatHistoryComposite = Composite(chatHistoryScroll, SWT.NONE).apply {
            layout = GridLayout(1, false).apply {
                marginWidth = 0
                marginHeight = 0
                horizontalSpacing = 2
                verticalSpacing = 2
            }

            layoutData = GridData().apply {
                widthHint = 660
            }
        }

        chatHistoryScroll.content = chatHistoryComposite

//
//        val document = Document().apply {
//            set("public class Example {\n\tpublic static void main(String[] args) {\n\t\t// Your code here\n\t}\n}")
//        }

        // for the rest
//       val sourceViewerConfiguration = TextSourceViewerConfiguration()

        // for Java code
//        val preferenceStore = JavaPlugin.getDefault().preferenceStore
//        val javaTextTools = JavaTextTools(JavaPlugin.getDefault().preferenceStore)
//
//        val sourceViewerConfiguration: SourceViewerConfiguration = SimpleJavaSourceViewerConfiguration(
//            javaTextTools.colorManager,
//            preferenceStore,
//            null,
//            null,
//            false
//        )
//
//        SourceViewer(composite, null, SWT.V_SCROLL or SWT.H_SCROLL).apply {
//            configure(sourceViewerConfiguration)
//            this.document = document
//            this.isEditable = false
//            this.control.layoutData = GridData(GridData.FILL_HORIZONTAL).apply {
//                widthHint = 580
//            }
//        }

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
                } else if(inputField.text.isNotBlank()) {
                    // send message
                    val chatId = UUID.randomUUID().toString()

                    val userMessage = Text(chatHistoryComposite, SWT.SINGLE).apply {
                        editable = false
                        text = "> ${inputField.text}"
                    }

                    val duoResponse = Text(chatHistoryComposite, SWT.MULTI or SWT.WRAP).apply {
                        editable = false
                        text = "Duo[${chatId}]: ... waiting for answer"

                        layoutData = GridData(GridData.FILL_BOTH).apply {
                            widthHint = 660
                        }

                        addListener(SWT.MouseVerticalWheel) { event ->
                            val scrollAmount = -(event.count * 30)
                            val origin = chatHistoryScroll.origin

                            val size = chatHistoryComposite.computeSize(SWT.DEFAULT, SWT.DEFAULT)
                            val clientY = chatHistoryScroll.clientArea.y
                            val maxY = max(0, size.y - clientY)

                            val newY = (origin.y + scrollAmount).coerceAtMost(maxY)
                            chatHistoryScroll.setOrigin(origin.x, newY)

                            event.doit = false
                        }
                    }

                    Thread.ofVirtual().start {
                        chatApi.chat { chunk ->
                            Display.getDefault().asyncExec { // Update your UI components here, safely on the UI thread
                                duoResponse.text += chunk

                                val height = chatHistoryComposite.computeSize(SWT.DEFAULT, SWT.DEFAULT).y.coerceAtMost(300)
                                chatHistoryScroll.setMinSize(chatHistoryComposite.computeSize(660, height))

                                chatHistoryComposite.pack(true)
                                composite.pack(true)
                            }
                        }
                    }

                    inputField.text = ""
                    composite.pack(true)
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

//            chatHistoryScroll.background = Display.getCurrent().getSystemColor(SWT.COLOR_RED)
        }

        textWidget.redraw()
    }
}