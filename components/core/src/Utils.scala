package ch.epfl.yinyang

import ch.epfl.yinyang.transformers._
import scala.reflect.macros.blackbox.Context
import language.experimental.macros
import scala.util.matching.Regex

trait MacroModule {
  type Ctx <: Context
  val c: Ctx
}

trait DataDefs extends MacroModule {
  import c.universe._
  case class DSLFeature(tpe: Option[Type], name: String, targs: List[Tree], args: List[List[Type]])
}

/**
 * Common utilities for the Yin-Yang project.
 */
trait TransformationUtils extends MacroModule {
  import c.universe._
  import internal.decorators._

  private val functionArity = 22
  private val functionSymbols = (0 to functionArity)
    .map(x => "scala.Function" + x)
    .map(c.mirror.staticClass)

  def isFunction(methodSymbol: Symbol): Boolean =
    functionSymbols.contains(methodSymbol.owner)

  object MultipleTypeApply {

    def apply(lhs: Tree, targs: List[Tree], argss: List[List[Tree]]): Tree = {
      val tpeApply = if (targs.isEmpty) lhs else TypeApply(lhs, targs)
      argss.foldLeft(tpeApply)((agg, args) => Apply(agg, args))
    }

    def unapply(value: Tree): Option[(Tree, List[Tree], List[List[Tree]])] = value match {
      case Apply(x, y) =>
        Some(x match {
          case MultipleTypeApply(lhs, targs, argss) =>
            (lhs, targs, y :: argss)
          case TypeApply(lhs, targs) =>
            (lhs, targs, Nil)
          case _ =>
            (x, Nil, y :: Nil)
        })

      case TypeApply(lhs, targs) =>
        Some((lhs, targs, Nil))

      case _ => None
    }
  }

  /* These two should be unified */
  def method(recOpt: Option[Tree], methName: String, args: List[List[Tree]], targs: List[Tree] = Nil): Tree = {
    val calleeName = TermName(methName)
    val callee = recOpt match {
      case Some(rec) => Select(rec, calleeName)
      case None      => Ident(calleeName)
    }
    val calleeAndTargs: Tree = typeApply(targs)(callee)
    args.foldLeft(calleeAndTargs) { Apply(_, _) }
  }

  private[yinyang] def symbolId(symbol: Symbol): Int =
    symbol.asInstanceOf[scala.reflect.internal.Symbols#Symbol].id

  private[yinyang] def symbolId(tree: Tree): Int = symbolId(tree.symbol)

  def typeApply(targs: List[Tree])(select: Tree) = if (targs.nonEmpty)
    TypeApply(select, targs)
  else
    select

  def typeToTree(tpe: Type): Tree = tpe match {
    case TypeRef(pre, sym, Nil) =>
      TypeTree(tpe)
    case TypeRef(pre, sym, args) =>
      AppliedTypeTree(Ident(sym.name),
        args map { x => typeToTree(x) })
    case AnnotatedType(annotations, underlying) =>
      typeToTree(underlying)
    case _ => TypeTree(tpe)
  }

  def makeConstructor(classname: String, arguments: List[Tree]): Tree =
    Apply(Select(newClass(classname), termNames.CONSTRUCTOR), arguments)

  def newClass(classname: String) =
    New(Ident(TypeName(classname)))

  def copy(orig: Tree)(nev: Tree): Tree = {
    nev.setSymbol(orig.symbol)
    nev.setPos(orig.pos)
    nev
  }

  def deepDealias(tpe: Type): Type = {
    val dealiased = tpe.dealias.map(_.dealias)
    if (dealiased == tpe) tpe
    else deepDealias(dealiased)
  }

  def log(s: => String, level: Int = 0) = if (debugLevel > level) println(s)

  def debugLevel: Int

  /*
   * Utility methods for logging.
   */
  def className: String = ???
  lazy val typeRegex = new Regex("(" + className.replace("$", "\\$") + """\.this\.)(\w*)""")
  lazy val typetagRegex = new Regex("""(scala\.reflect\.runtime\.[a-zA-Z`]*\.universe\.typeTag\[)(\w*)\]""")
  def code(tree: Tree, shortenDSLNames: Boolean = true): String = {
    var short = showCode(tree)
    if (shortenDSLNames) {
      typeRegex findAllIn short foreach { m =>
        val typeRegex(start, typ) = m
        short = short.replace(start + typ, typ.toUpperCase())
      }
      typetagRegex findAllIn short foreach { m =>
        val typetagRegex(start, typ) = m
        short = short.replace(start + typ + "]", "TYPETAG[" + typ.toUpperCase() + "]")
      }
    }
    short
  }
}
