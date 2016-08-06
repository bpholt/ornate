package com.novocode.mdoc.theme

import java.net.URI
import java.util.Collections

import com.novocode.mdoc._
import com.novocode.mdoc.commonmark.NodeExtensionMethods._

import better.files._
import com.novocode.mdoc.commonmark._
import com.novocode.mdoc.config.Global
import org.commonmark.html.HtmlRenderer
import org.commonmark.html.HtmlRenderer.HtmlRendererExtension
import org.commonmark.html.renderer.{NodeRendererContext, NodeRenderer}
import org.commonmark.node.HtmlBlock
import play.twirl.api.{Html, Template1, HtmlFormat}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Codec

abstract class Theme(global: Global) {
  def render(site: Site): Unit

  def synthesizePages(uris: Vector[(String, URI)]): Vector[Page] = Vector.empty

  def syntheticPageURIs: Vector[(String, URI)] = {
    if(global.userConfig.themeConfig.hasPath("global.pages")) {
      val co = global.userConfig.themeConfig.getObject("global.pages")
      co.entrySet().asScala.iterator.filter(_.getValue.unwrapped ne null).map(e =>
        (e.getKey, Util.siteRootURI.resolve(e.getValue.unwrapped.asInstanceOf[String]))
      ).toVector
    } else Vector.empty
  }
}

/** Base class for Twirl-based HTML themes */
class HtmlTheme(global: Global) extends Theme(global) with Logging { self =>
  import HtmlTheme._
  val suffix = ".html"

  val attributedHeadingRenderer = SimpleHtmlNodeRenderer { (n: AttributedHeading, c: NodeRendererContext) =>
    val html = c.getHtmlWriter
    val htag = s"h${n.getLevel}"
    html.line
    val attrs = c.extendAttributes(n, Collections.emptyMap[String, String])
    if(n.id ne null) attrs.put("id", n.id)
    html.tag(htag, attrs)
    n.children.toVector.foreach(c.render)
    html.tag('/' + htag)
    html.line
  }


  def render(site: Site): Unit = {
    val slp = new SpecialLinkProcessor(site, suffix)
    site.pages.foreach { p =>
      slp(p)
      val file = p.targetFile(global.userConfig.targetDir, suffix)
      val templateName = p.config.getString("template")
      logger.debug(s"Rendering page ${p.uri} to file $file with template ${templateName}")
      val template = getTemplate(templateName)
      val _renderer = HtmlRenderer.builder()
        .nodeRendererFactory(attributedHeadingRenderer).extensions(p.extensions.htmlRenderer.asJava).build()
      val pm: PageModel = new PageModel {
        def renderer = _renderer
        def theme = self
        val title = HtmlFormat.escape(p.section.title.getOrElse(""))
        val content = HtmlFormat.raw(renderer.render(p.doc))
      }
      val formatted = template.render(pm).body.trim
      file.parent.createDirectories()
      file.write(formatted+'\n')(codec = Codec.UTF8)
    }
  }

  private[this] val templateBase = getClass.getName.replaceAll("\\.[^\\.]*$", "")
  private[this] val templates = new mutable.HashMap[String, Template]
  def getTemplate(name: String) = templates.getOrElseUpdate(name, {
    val className = s"$templateBase.$name"
    logger.debug("Creating template from class "+className)
    Class.forName(className).newInstance().asInstanceOf[Template]
  })
}

/** A theme that prints the document structure to stdout */
class Dump(global: Global) extends Theme(global) {
  def render(site: Site): Unit = {
    site.pages.foreach { p =>
      println("---------- Page: "+p.uri)
      p.doc.dumpDoc()
    }
  }
}

object HtmlTheme {
  type Template = Template1[PageModel, HtmlFormat.Appendable]

  trait PageModel {
    def renderer: HtmlRenderer
    def theme: HtmlTheme
    def title: Html
    def content: Html
  }
}
