package jbok.app.views

import com.thoughtworks.binding
import com.thoughtworks.binding.Binding
import com.thoughtworks.binding.Binding.{Var, Vars}
import org.scalajs.dom._

object Nav {
  final case class Tab(name: String, content: Var[Binding[Node]], icon: String)
  final case class TabList(tabs: Vars[Tab], selected: Var[Tab])
  @binding.dom
  def renderEmpty: Binding[Node] =
    <div></div>

  @binding.dom
  def render(left: Binding[Node], right: Binding[Node] = renderEmpty): Binding[Node] =
    <nav>
      {left.bind}
      {right.bind}
    </nav>
}
