package com.gitlab.eclipse.views.sidebar

import org.eclipse.jface.viewers.ColumnLabelProvider
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Image

/**
 * Renders each [SidebarNode] (design A D1-D3): [sidebarDisplayText] as text, the node's CI status icon as image and
 * the change badge's theme color as foreground. No owner draw.
 *
 * [image] creates the icon for a registry name; it runs once per name through an [IconImageCache] (a failure is
 * reported once through [onImageError] and drawn without an image). The images belong to the caller's resource
 * manager: [dispose] only forgets them. [color] is asked on every paint and never cached (the theme's
 * ColorRegistry owns it). The defaults draw neither, so a provider built without them renders text only.
 */
class SidebarLabelProvider(
  image: (String) -> Image? = { null },
  private val color: (ChangeColorRole) -> Color? = { null },
  onImageError: (String, Throwable) -> Unit = { _, _ -> },
) : ColumnLabelProvider() {
  private val images = IconImageCache(image, onImageError)

  override fun getText(element: Any?): String = (element as? SidebarNode)?.let(::sidebarDisplayText) ?: ""

  override fun getImage(element: Any?): Image? =
    (element as? SidebarNode)?.let(::sidebarStatusIcon)?.let { images.get(it.iconName) }

  override fun getForeground(element: Any?): Color? =
    (element as? SidebarNode)?.let(::changeBadge)?.colorRole?.let(color)

  override fun dispose() {
    images.clear()
    super.dispose()
  }
}
