interface Todo {
  id: number;
  title: string;
  done: boolean;
}

type Filter = "all" | "pending" | "done";

export function visible(todos: readonly Todo[], filter: Filter = "all"): Todo[] {
  switch (filter) {
    case "pending":
      return todos.filter((t) => !t.done);
    case "done":
      return todos.filter((t) => t.done);
    default:
      return [...todos];
  }
}

export class TodoStore<T extends Todo = Todo> {
  private items: T[] = [];
  constructor(private readonly name: string) {}
  add(item: T): void {
    this.items.push(item);
  }
}

const todos: Todo[] = [{ id: 1, title: "Write the editor", done: true }];
console.log(visible(todos, "pending").length as number);
