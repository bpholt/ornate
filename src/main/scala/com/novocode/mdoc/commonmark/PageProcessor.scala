package com.novocode.mdoc.commonmark

import java.net.URI
import java.util.Locale

import com.novocode.mdoc._
import NodeExtensionMethods._
import com.novocode.mdoc.config.UserConfig

import org.commonmark.node._

import scala.collection.mutable.ArrayBuffer

abstract class PageProcessor extends (Page => Unit) with Logging

class SpecialImageProcessor(config: UserConfig) extends PageProcessor {
  val SpecialObjectMatcher = new SpecialImageParagraphMatcher(Set("toctree"))

  def apply(p: Page): Unit = p.doc.accept(new AbstractVisitor {
    override def visit(n: Paragraph): Unit = n match {
      case SpecialObjectMatcher(r) => try {
        r.protocol match {
          case "toctree" =>
            val t = SpecialImageProcessor.parseTocURI(r.image.getDestination, config)
            t.title = r.title
            n.replaceWith(t)
            r.image.children.foreach(t.appendChild)
          case _ =>
        }
      } catch { case ex: Exception => logger.error("Error expanding TOC tree "+r.dest, ex) }
      case n => super.visit(n)
    }
    override def visit(n: Image): Unit = {
      n match {
        case SpecialObjectMatcher.ImageMatcher(r) =>
          logger.error(s"Page ${p.uri}: Illegal inline special object ${r.image.getDestination} -- only block-level allowed")
        case n =>
      }
      super.visit(n)
    }
  })
}

object SpecialImageProcessor {
  def parseTocURI(link: String, config: UserConfig): TocBlock = {
    val uri = if(link == "toctree:") new URI("toctree:default") else new URI(link)
    val scheme = uri.getScheme
    assert(scheme == "toctree")
    val attributes = uri.getSchemeSpecificPart.split(',').filter(_.nonEmpty).flatMap { s =>
      val sep = s.indexOf('=')
      if(sep == -1) None else Some((s.substring(0, sep).toLowerCase(Locale.ENGLISH), s.substring(sep+1)))
    }.toMap
    val maxLevel = attributes.get("maxlevel").map(_.toInt).getOrElse(config.tocMaxLevel)
    new TocBlock(
      maxLevel,
      attributes.get("mergefirst").map(_.toBoolean).getOrElse(config.tocMergeFirst),
      attributes.get("local").map(_.toBoolean).getOrElse(false),
      attributes.get("focusmaxlevel").map(_.toInt).getOrElse(maxLevel)
    )
  }

}

class ExpandTocProcessor(toc: Vector[TocEntry]) extends PageProcessor {
  import ExpandTocProcessor._

  def log(prefix: String, ti: TocItem): Unit = {
    logger.debug(s"""$prefix- "${ti.text.getOrElse("")}" -> ${ti.target.getOrElse("")}""")
    ti.children.foreach(ch => log(prefix+"  ", ch))
  }

  def apply(p: Page): Unit = {
    p.doc.accept(new AbstractVisitor {
      override def visit(n: CustomBlock): Unit = n match {
        case n: TocBlock =>
          val items = buildTocTree(n, toc, p)
          if(items.nonEmpty) {
            val ul = new BulletList
            ul.setTight(true)
            items.foreach { i =>
              log("", i)
              ul.appendChild(i.toNode)
            }
            n.replaceWith(ul)
          } else n.unlink()
        case _ =>
          super.visit(n)
      }
    })
  }
}

object ExpandTocProcessor {
  case class TocItem(text: Option[String], target: Option[String], children: Vector[TocItem], focused: Boolean) {
    def toNode: ListItem = {
      val li = new ListItem
      text.foreach { t =>
        val p = new Paragraph
        li.appendChild(p)
        val text = new Text(t)
        target match {
          case Some(dest) =>
            val link = new Link(dest, null)
            p.appendChild(link)
            link.appendChild(text)
          case None =>
            p.appendChild(text)
        }
      }
      if(children.nonEmpty) {
        val ul = new BulletList
        ul.setTight(true)
        li.appendChild(ul)
        children.foreach(ch => ul.appendChild(ch.toNode))
      }
      li
    }
  }

  /** Turn a `Section` recursively into a `TocItem` tree */
  private def sectionToc(s: Section, p: Page, pTitle: Option[String], maxLevel: Int, focused: Boolean): Option[TocItem] = {
    if(s.level > maxLevel) None else {
      val (title, target) = s match {
        case PageSection(title, children) => (pTitle, Some(p.uri.toString))
        case UntitledSection(_, children) => (None, None)
        case s @ HeadingSection(id, _, title, children) =>
          val id = s.heading match {
            case h: AttributedHeading => Option(h.id)
            case _ => None
          }
          (Some(title), id.map(p.uri.toString + "#" + _))
      }
      val ch = s.children.flatMap(ch => sectionToc(ch, p, pTitle, maxLevel, focused))
      if(title.isEmpty && target.isEmpty && ch.isEmpty) None
      else Some(TocItem(title, target, ch, focused))
    }
  }

  /** Remove items without a title and move their children into the preceding item */
  private def mergeHierarchies(items: Vector[TocItem]): Vector[TocItem] = {
    val b = new ArrayBuffer[TocItem](items.length)
    items.foreach { item =>
      if(b.isEmpty || item.text.nonEmpty || item.target.nonEmpty) b += item
      else {
        val prev = b.remove(b.length-1)
        b += prev.copy(children = mergeHierarchies(prev.children ++ item.children))
      }
    }
    b.toVector
  }

  /** Merge top-level `TocItem` for the page itself with the first element of the page */
  private def mergePages(items: Vector[TocItem]): Vector[TocItem] = mergeHierarchies(items.flatMap { pageItem =>
    def rewriteFirstIn(items: Vector[TocItem]): Vector[TocItem] = {
      if(items.isEmpty) items // should never happen
      else {
        val h = items.head
        if(h.text.nonEmpty || h.target.nonEmpty || h.children.isEmpty)
          h.copy(text = pageItem.text.orElse(h.text), target = pageItem.target) +: items.tail
        else h.copy(children = rewriteFirstIn(h.children)) +: items.tail
      }
    }
    rewriteFirstIn(pageItem.children)
  })

  def buildTocTree(n: TocBlock, toc: Vector[TocEntry], page: Page): Vector[TocItem] = {
    if(n.local) {
      val title = toc.find(_.page eq page) match {
        case Some(e) => e.title
        case None => page.section.title
      }
      sectionToc(page.section, page, title, n.focusMaxLevel, true).toVector.flatMap(_.children)
    } else {
      val items = toc.flatMap(e => sectionToc(e.page.section, e.page, e.title, if(e.page eq page) n.focusMaxLevel else n.maxLevel, e.page eq page))
      if(n.mergeFirst) mergePages(items) else items
    }
  }
}
